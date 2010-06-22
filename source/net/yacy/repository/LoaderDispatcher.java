// LoaderDispatcher.java
// (C) 2007 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 24.10.2007 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.repository;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.yacy.cora.document.MultiProtocolURI;
import net.yacy.document.Document;
import net.yacy.document.TextParser;
import net.yacy.document.ParserException;
import net.yacy.document.parser.html.ContentScraper;
import net.yacy.document.parser.html.TransformerWriter;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.util.Domains;
import net.yacy.kelondro.util.FileUtils;

import de.anomic.crawler.CrawlProfile;
import de.anomic.crawler.retrieval.FTPLoader;
import de.anomic.crawler.retrieval.FileLoader;
import de.anomic.crawler.retrieval.HTTPLoader;
import de.anomic.crawler.retrieval.Request;
import de.anomic.crawler.retrieval.Response;
import de.anomic.crawler.retrieval.SMBLoader;
import de.anomic.http.client.Cache;
import de.anomic.http.server.HeaderFramework;
import de.anomic.http.server.RequestHeader;
import de.anomic.http.server.ResponseHeader;
import de.anomic.search.Segments;
import de.anomic.search.Switchboard;

public final class LoaderDispatcher {

    private static final long minDelay = 250; // milliseconds; 4 accesses per second
    private static final ConcurrentHashMap<String, Long> accessTime = new ConcurrentHashMap<String, Long>(); // to protect targets from DDoS
    
    private final Switchboard sb;
    private final HashSet<String> supportedProtocols;
    private final HTTPLoader httpLoader;
    private final FTPLoader ftpLoader;
    private final SMBLoader smbLoader;
    private final FileLoader fileLoader;
    private final Log log;
    
    public LoaderDispatcher(final Switchboard sb) {
        this.sb = sb;
        this.supportedProtocols = new HashSet<String>(Arrays.asList(new String[]{"http","https","ftp","smb","file"}));
        
        // initiate loader objects
        this.log = new Log("LOADER");
        httpLoader = new HTTPLoader(sb, log);
        ftpLoader = new FTPLoader(sb, log);
        smbLoader = new SMBLoader(sb, log);
        fileLoader = new FileLoader(sb, log);
    }
    
    public boolean isSupportedProtocol(final String protocol) {
        if ((protocol == null) || (protocol.length() == 0)) return false;
        return this.supportedProtocols.contains(protocol.trim().toLowerCase());
    }
    
    @SuppressWarnings("unchecked")
    public HashSet<String> getSupportedProtocols() {
        return (HashSet<String>) this.supportedProtocols.clone();
    }

    /**
     * generate a request object
     * @param url the target url
     * @param forText shows that this was a for-text crawling request
     * @param global shows that this was a global crawling request
     * @return the request object
     */
    public Request request(
            final DigestURI url,
            final boolean forText,
            final boolean global
                    ) {
        return new Request(
                    sb.peers.mySeed().hash.getBytes(), 
                    url, 
                    null, 
                    "", 
                    new Date(),
                    (forText) ?
                        ((global) ?
                            sb.crawler.defaultTextSnippetGlobalProfile.handle() :
                            sb.crawler.defaultTextSnippetLocalProfile.handle())
                        :
                        ((global) ?
                            sb.crawler.defaultMediaSnippetGlobalProfile.handle() :
                            sb.crawler.defaultMediaSnippetLocalProfile.handle()), // crawl profile
                    0, 
                    0, 
                    0);
    }

    public void load(final DigestURI url, CrawlProfile.CacheStrategy cacheStratgy, long maxFileSize, File targetFile) throws IOException {

        byte[] b = load(request(url, false, true), cacheStratgy, maxFileSize).getContent();
        if (b == null) throw new IOException("load == null");
        File tmp = new File(targetFile.getAbsolutePath() + ".tmp");
        
        // transaction-safe writing
        File parent = targetFile.getParentFile();
        if (!parent.exists()) parent.mkdirs();
        FileUtils.copy(b, tmp);
        tmp.renameTo(targetFile);
    }
    
    /**
     * load a resource from the web, from ftp, from smb or a file
     * @param request the request essentials
     * @param cacheStratgy strategy according to CACHE_STRATEGY_NOCACHE,CACHE_STRATEGY_IFFRESH,CACHE_STRATEGY_IFEXIST,CACHE_STRATEGY_CACHEONLY
     * @return the loaded entity in a Response object
     * @throws IOException
     */
    public Response load(final Request request, CrawlProfile.CacheStrategy cacheStrategy, long maxFileSize) throws IOException {
        // get the protocol of the next URL
        final String protocol = request.url().getProtocol();
        final String host = request.url().getHost();
        
        // check if this loads a page from localhost, which must be prevented to protect the server
        // against attacks to the administration interface when localhost access is granted
        if (Domains.isLocal(host) && sb.getConfigBool("adminAccountForLocalhost", false)) throw new IOException("access to localhost not granted for url " + request.url());
        
        // check if we have the page in the cache

        CrawlProfile.entry crawlProfile = sb.crawler.profilesActiveCrawls.getEntry(request.profileHandle());
        if (crawlProfile != null && cacheStrategy != CrawlProfile.CacheStrategy.NOCACHE) {
            // we have passed a first test if caching is allowed
            // now see if there is a cache entry
        
            ResponseHeader cachedResponse = (request.url().isLocal()) ? null : Cache.getResponseHeader(request.url());
            byte[] content = (cachedResponse == null) ? null : Cache.getContent(request.url());
            if (cachedResponse != null && content != null) {
                // yes we have the content
                
                // create request header values and a response object because we need that
                // in case that we want to return the cached content in the next step
                final RequestHeader requestHeader = new RequestHeader();
                requestHeader.put(HeaderFramework.USER_AGENT, HTTPLoader.crawlerUserAgent);
                DigestURI refererURL = null;
                if (request.referrerhash() != null) refererURL = sb.getURL(Segments.Process.LOCALCRAWLING, request.referrerhash());
                if (refererURL != null) requestHeader.put(RequestHeader.REFERER, refererURL.toNormalform(true, true));
                Response response = new Response(
                        request,
                        requestHeader,
                        cachedResponse,
                        "200",
                        crawlProfile,
                        content);
                
                // check which caching strategy shall be used
                if (cacheStrategy == CrawlProfile.CacheStrategy.IFEXIST || cacheStrategy == CrawlProfile.CacheStrategy.CACHEONLY) {
                    // well, just take the cache and don't care about freshness of the content
                    log.logInfo("cache hit/useall for: " + request.url().toNormalform(true, false));
                    return response;
                }
                
                // now the cacheStrategy must be CACHE_STRATEGY_IFFRESH, that means we should do a proxy freshness test
                assert cacheStrategy == CrawlProfile.CacheStrategy.IFFRESH : "cacheStrategy = " + cacheStrategy;
                if (response.isFreshForProxy()) {
                    log.logInfo("cache hit/fresh for: " + request.url().toNormalform(true, false));
                    return response;
                } else {
                    log.logInfo("cache hit/stale for: " + request.url().toNormalform(true, false));
                }
            }
        }
        
        // check case where we want results from the cache exclusively, and never from the internet (offline mode)
        if (cacheStrategy == CrawlProfile.CacheStrategy.CACHEONLY) {
            // we had a chance to get the content from the cache .. its over. We don't have it.
            throw new IOException("cache only strategy");
        }
        
        // now forget about the cache, nothing there. Try to load the content from the internet
        
        // check access time: this is a double-check (we checked possibly already in the balancer)
        // to make sure that we don't DoS the target by mistake
        if (!request.url().isLocal()) {
            final Long lastAccess = accessTime.get(host);
            long wait = 0;
            if (lastAccess != null) wait = Math.max(0, minDelay + lastAccess.longValue() - System.currentTimeMillis());
            if (wait > 0) {
                // force a sleep here. Instead just sleep we clean up the accessTime map
                final long untilTime = System.currentTimeMillis() + wait;
                cleanupAccessTimeTable(untilTime);
                if (System.currentTimeMillis() < untilTime)
                    try {Thread.sleep(untilTime - System.currentTimeMillis());} catch (final InterruptedException ee) {}
            }
        }

        // now it's for sure that we will access the target. Remember the access time
        if (host != null) accessTime.put(host, System.currentTimeMillis());
        
        // load resource from the internet
        Response response = null;
        if ((protocol.equals("http") || (protocol.equals("https")))) response = httpLoader.load(request, maxFileSize);
        if (protocol.equals("ftp")) response = ftpLoader.load(request, true);
        if (protocol.equals("smb")) response = smbLoader.load(request, true);
        if (protocol.equals("file")) response = fileLoader.load(request, true);
        if (response != null) {
            // we got something. Now check if we want to store that to the cache
            // first check looks if we want to store the content to the cache
            if (!crawlProfile.storeHTCache()) {
                // no caching wanted. Thats ok, do not write any message
                return response;
            }
            // second check tells us if the protocoll tells us something about caching
            String storeError = response.shallStoreCacheForCrawler();
            if (storeError == null) {
                try {
                    Cache.store(request.url(), response.getResponseHeader(), response.getContent());
                } catch (IOException e) {
                    log.logWarning("cannot write " + response.url() + " to Cache (3): " + e.getMessage(), e);
                }
            } else {
                log.logWarning("cannot write " + response.url() + " to Cache (4): " + storeError);
            }
            return response;
        }
        
        throw new IOException("Unsupported protocol '" + protocol + "' in url " + request.url());
    }

    /**
     * load the url as byte[] content from the web or the cache
     * @param request
     * @param cacheStrategy
     * @param timeout
     * @return the content as {@link byte[]}
     * @throws IOException 
     */
    public byte[] loadContent(final Request request, CrawlProfile.CacheStrategy cacheStrategy) throws IOException {
        // try to download the resource using the loader
        final long maxFileSize = sb.getConfigLong("crawler.http.maxFileSize", HTTPLoader.DEFAULT_MAXFILESIZE);
        final Response entry = load(request, cacheStrategy, maxFileSize);
        if (entry == null) return null; // not found in web
        
        // read resource body (if it is there)
        return entry.getContent();
    }
    
    public Document loadDocument(final Request request, final CrawlProfile.CacheStrategy cacheStrategy, final int timeout, long maxFileSize) throws IOException, ParserException {

        // load resource
        final Response response = load(request, cacheStrategy, maxFileSize);
        if (response == null) throw new IOException("no Response for url " + request.url());

        // if it is still not available, report an error
        if (response.getContent() == null || response.getResponseHeader() == null) throw new IOException("no Content available for url " + request.url());

        // parse resource
        return response.parse();
    }

    public ContentScraper parseResource(final DigestURI location, CrawlProfile.CacheStrategy cachePolicy) throws IOException {
        // load page
        final long maxFileSize = this.sb.getConfigLong("crawler.http.maxFileSize", HTTPLoader.DEFAULT_MAXFILESIZE);
        Response r = this.load(request(location, true, false), cachePolicy, maxFileSize);
        byte[] page = (r == null) ? null : r.getContent();
        if (page == null) throw new IOException("no response from url " + location.toString());
        
        // scrape content
        final ContentScraper scraper = new ContentScraper(location);
        final Writer writer = new TransformerWriter(null, null, scraper, null, false);
        writer.write(new String(page, "UTF-8"));
        
        return scraper;
    }

    /**
     * load all links from a resource
     * @param url the url that shall be loaded
     * @param cacheStrategy the cache strategy
     * @return a map from URLs to the anchor texts of the urls
     * @throws IOException
     */
    public final Map<MultiProtocolURI, String> loadLinks(DigestURI url, CrawlProfile.CacheStrategy cacheStrategy) throws IOException {
        Response response = load(request(url, true, false), cacheStrategy, Long.MAX_VALUE);
        if (response == null) throw new IOException("response == null");
        ResponseHeader responseHeader = response.getResponseHeader();
        byte[] resource = response.getContent();
        if (resource == null) throw new IOException("resource == null");
        if (responseHeader == null) throw new IOException("responseHeader == null");
    
        Document document = null;
        String supportError = TextParser.supports(url, responseHeader.mime());
        if (supportError != null) throw new IOException("no parser support: " + supportError);
        try {
            document = TextParser.parseSource(url, responseHeader.mime(), responseHeader.getCharacterEncoding(), resource.length, new ByteArrayInputStream(resource));
            if (document == null) throw new IOException("document == null");
        } catch (final ParserException e) {
            throw new IOException("parser error: " + e.getMessage());
        } catch (InterruptedException e) {
            throw new IOException("interrupted");
        } finally {
            resource = null;
        }

        Map<MultiProtocolURI, String> result = document.getHyperlinks();
        document.close();
        return result;
    }
    
    public synchronized void cleanupAccessTimeTable(long timeout) {
    	final Iterator<Map.Entry<String, Long>> i = accessTime.entrySet().iterator();
        Map.Entry<String, Long> e;
        while (i.hasNext()) {
            e = i.next();
            if (System.currentTimeMillis() > timeout) break;
            if (System.currentTimeMillis() - e.getValue().longValue() > minDelay) i.remove();
        }
    }
    
    public void loadIfNotExistBackground(String url, File cache, long maxFileSize) {
        new Loader(url, cache, maxFileSize).start();
    }
    
    private class Loader extends Thread {

        private String url;
        private File cache;
        private long maxFileSize;
        
        public Loader(String url, File cache, long maxFileSize) {
            this.url = url;
            this.cache = cache;
            this.maxFileSize = maxFileSize;
        }
        
        public void run() {
            if (this.cache.exists()) return;
            try {
                // load from the net
                Response response = load(request(new DigestURI(this.url), false, true), CrawlProfile.CacheStrategy.NOCACHE, this.maxFileSize);
                byte[] b = response.getContent();
                FileUtils.copy(b, this.cache);
            } catch (MalformedURLException e) {} catch (IOException e) {}
        }
    }
}