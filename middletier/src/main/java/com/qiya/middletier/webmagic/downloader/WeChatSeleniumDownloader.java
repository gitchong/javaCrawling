package com.qiya.middletier.webmagic.downloader;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.downloader.AbstractDownloader;
import us.codecraft.webmagic.downloader.Downloader;
import us.codecraft.webmagic.downloader.HttpClientGenerator;
import us.codecraft.webmagic.proxy.Proxy;
import us.codecraft.webmagic.selector.Html;
import us.codecraft.webmagic.selector.PlainText;
import us.codecraft.webmagic.utils.UrlUtils;
import us.codecraft.webmagic.utils.WMCollections;

import javax.annotation.PostConstruct;

/**
 * 使用Selenium调用浏览器进行渲染。目前仅支持chrome。<br>
 * 需要下载Selenium driver支持。<br>
 *
 * @author code4crafter@gmail.com <br>
 *         Date: 13-7-26 <br>
 *         Time: 下午1:37 <br>
 */
@Service
@Scope("prototype")
public class WeChatSeleniumDownloader extends AbstractDownloader implements Downloader, Closeable  {

	private volatile WebDriverPool webDriverPool;

	private org.slf4j.Logger logger = LoggerFactory.getLogger(this.getClass());

	private int sleepTime = 0;

	private int poolSize = 1;

	private static final String DRIVER_PHANTOMJS = "phantomjs";

    @Value("${selenuim_config:}")
    private String selenuim_config;

    @Value("${driverPath:}")
    private String driverPath;

    @PostConstruct
    public void init() {
        System.setProperty("webdriver.chrome.driver", driverPath);
        System.setProperty("selenuim_config", selenuim_config);
    }

	/**
	 * 新建
	 *
	 * @param chromeDriverPath chromeDriverPath
	 */
	public WeChatSeleniumDownloader(String chromeDriverPath) {
		System.getProperties().setProperty("webdriver.chrome.driver", chromeDriverPath);
	}

	/**
	 * Constructor without any filed. Construct PhantomJS browser
	 *
	 * @author bob.li.0718@gmail.com
	 */
	public WeChatSeleniumDownloader() {
		// System.setProperty("phantomjs.binary.path",
		// "/Users/Bingo/Downloads/phantomjs-1.9.7-macosx/bin/phantomjs");
	}

	/**
	 * set sleep time to wait until load success
	 *
	 * @param sleepTime sleepTime
	 * @return this
	 */
	public WeChatSeleniumDownloader setSleepTime(int sleepTime) {
		this.sleepTime = sleepTime;
		return this;
	}

	@Override
	public Page download(Request request, Task task) {
		Site 	site=null;
		if (task != null) {
			site = task.getSite();
		}

		if(request.getUrl().indexOf("http://mp.weixin.qq.com/s?__biz=")==0 ||request.getUrl().indexOf("http://mp.weixin.qq.com/s?")==0) {
            Page page = new Page();
			checkInit();
			WebDriver webDriver;
			try {
				webDriver = webDriverPool.get();
			} catch (InterruptedException e) {
				logger.warn("interrupted", e);
				return null;
			}
			logger.info("downloading page " + request.getUrl());
			String url=request.getUrl();
			if(request.getUrl().indexOf("图片")>0){
                String urls[]= request.getUrl().split("图片");
                url=urls[0];
                request.setUrl(url);
                page.putField("pic",urls[1]);
            }
			webDriver.get(url);

			try {
				Thread.sleep(sleepTime);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			WebDriver.Options manage = webDriver.manage();

			if (site.getCookies() != null) {
				for (Map.Entry<String, String> cookieEntry : site.getCookies().entrySet()) {
					Cookie cookie = new Cookie(cookieEntry.getKey(), cookieEntry.getValue());
					manage.addCookie(cookie);
				}
			}

		/*
		 * TODO You can add mouse event or other processes
		 *
		 * @author: bob.li.0718@gmail.com
		 */

			WebElement webElement = webDriver.findElement(By.xpath("/html"));
			String content = webElement.getAttribute("outerHTML");


            request.setUrl(webDriver.getCurrentUrl());
			page.setRawText(content);
			page.setHtml(new Html(UrlUtils.fixAllRelativeHrefs(content, request.getUrl())));
			page.setUrl(new PlainText(request.getUrl()));
			page.setRequest(request);
			webDriverPool.returnToPool(webDriver);
			return page;

		}else {

			String charset = null;
			Map headers = null;
			Set acceptStatCode;
			if (site != null) {
				acceptStatCode = site.getAcceptStatCode();
				charset = site.getCharset();
				headers = site.getHeaders();
			} else {
				acceptStatCode = WMCollections.newHashSet(new Integer[]{Integer.valueOf(200)});
			}

			this.logger.info("downloading page {}", request.getUrl());
			CloseableHttpResponse httpResponse = null;
			int statusCode = 0;

			Page proxy;
			try {
				try {
					HttpHost e = null;
					Proxy proxy1 = null;
					if (site.getHttpProxyPool() != null && site.getHttpProxyPool().isEnable()) {
						proxy1 = site.getHttpProxyFromPool();
						e = proxy1.getHttpHost();
					} else if (site.getHttpProxy() != null) {
						e = site.getHttpProxy();
					}

					HttpUriRequest e1 = this.getHttpUriRequest(request, site, headers, e);
					httpResponse = this.getHttpClient(site, proxy1).execute(e1);
					statusCode = httpResponse.getStatusLine().getStatusCode();
					request.putExtra("statusCode", Integer.valueOf(statusCode));
					Page page;
					if (this.statusAccept(acceptStatCode, statusCode)) {
						page = this.handleResponse(request, charset, httpResponse, task);
						this.onSuccess(request);
						Page e2 = page;
						return e2;
					}

					this.logger.warn("get page {} error, status code {} ", request.getUrl(), Integer.valueOf(statusCode));
					page = null;
					return page;
				} catch (IOException var25) {
					this.logger.warn("download page {} error", request.getUrl(), var25);
					if (site.getCycleRetryTimes() > 0) {
						proxy = this.addToCycleRetry(request, site);
						return proxy;
					}
				}

				this.onError(request);
				proxy = null;
			} finally {
				request.putExtra("statusCode", Integer.valueOf(statusCode));
				if (site.getHttpProxyPool() != null && site.getHttpProxyPool().isEnable()) {
					site.returnHttpProxyToPool((HttpHost) request.getExtra("proxy"), ((Integer) request.getExtra("statusCode")).intValue());
				}

				try {
					if (httpResponse != null) {
						EntityUtils.consume(httpResponse.getEntity());
					}
				} catch (IOException var24) {
					this.logger.warn("close response fail", var24);
				}

			}
			return proxy;
		}



	}

	private void checkInit() {
		if (webDriverPool == null) {
			synchronized (this) {
				webDriverPool = new WebDriverPool(poolSize);
			}
		}
	}

	@Override
	public void setThread(int thread) {
		this.poolSize = thread;
		this.httpClientGenerator.setPoolSize(thread);
	}

	@Override
	public void close() throws IOException {
	    if(webDriverPool!=null)
		    webDriverPool.closeAll();
	}

	private final Map<String, CloseableHttpClient> httpClients = new HashMap();
	private HttpClientGenerator httpClientGenerator = new HttpClientGenerator();


	private CloseableHttpClient getHttpClient(Site site, Proxy proxy) {
		if(site == null) {
			return this.httpClientGenerator.getClient((Site)null, proxy);
		} else {
			String domain = site.getDomain();
			CloseableHttpClient httpClient = (CloseableHttpClient)this.httpClients.get(domain);
			if(httpClient == null) {
				synchronized(this) {
					httpClient = (CloseableHttpClient)this.httpClients.get(domain);
					if(httpClient == null) {
						httpClient = this.httpClientGenerator.getClient(site, proxy);
						this.httpClients.put(domain, httpClient);
					}
				}
			}

			return httpClient;
		}
	}





	protected boolean statusAccept(Set<Integer> acceptStatCode, int statusCode) {
		return acceptStatCode.contains(Integer.valueOf(statusCode));
	}

	protected HttpUriRequest getHttpUriRequest(Request request, Site site, Map<String, String> headers, HttpHost proxy) {
		RequestBuilder requestBuilder = this.selectRequestMethod(request).setUri( request.getUrl());
		if(headers != null) {
			Iterator requestConfigBuilder = headers.entrySet().iterator();

			while(requestConfigBuilder.hasNext()) {
				Map.Entry headerEntry = (Map.Entry)requestConfigBuilder.next();
				requestBuilder.addHeader((String)headerEntry.getKey(), (String)headerEntry.getValue());
			}
		}

		RequestConfig.Builder requestConfigBuilder1 = RequestConfig.custom().setConnectionRequestTimeout(site.getTimeOut()).setSocketTimeout(site.getTimeOut()).setConnectTimeout(site.getTimeOut()).setCookieSpec("best-match");
		if(proxy != null) {
			requestConfigBuilder1.setProxy(proxy);
			request.putExtra("proxy", proxy);
		}

		requestBuilder.setConfig(requestConfigBuilder1.build());
		return requestBuilder.build();
	}

	protected RequestBuilder selectRequestMethod(Request request) {
		String method = request.getMethod();
		if(method != null && !method.equalsIgnoreCase("GET")) {
			if(method.equalsIgnoreCase("POST")) {
				RequestBuilder requestBuilder = RequestBuilder.post();
				NameValuePair[] nameValuePair = (NameValuePair[])((NameValuePair[])request.getExtra("nameValuePair"));
				if(nameValuePair != null && nameValuePair.length > 0) {
					requestBuilder.addParameters(nameValuePair);
				}

				return requestBuilder;
			} else if(method.equalsIgnoreCase("HEAD")) {
				return RequestBuilder.head();
			} else if(method.equalsIgnoreCase("PUT")) {
				return RequestBuilder.put();
			} else if(method.equalsIgnoreCase("DELETE")) {
				return RequestBuilder.delete();
			} else if(method.equalsIgnoreCase("TRACE")) {
				return RequestBuilder.trace();
			} else {
				throw new IllegalArgumentException("Illegal HTTP Method " + method);
			}
		} else {
			return RequestBuilder.get();
		}
	}

	protected Page handleResponse(Request request, String charset, HttpResponse httpResponse, Task task) throws IOException {
		String content = this.getContent(charset, httpResponse);
		Page page = new Page();
		page.setRawText(content);
		page.setUrl(new PlainText(request.getUrl()));
		page.setRequest(request);
		page.setStatusCode(httpResponse.getStatusLine().getStatusCode());
		return page;
	}

	protected String getContent(String charset, HttpResponse httpResponse) throws IOException {
		if(charset == null) {
			byte[] contentBytes = IOUtils.toByteArray(httpResponse.getEntity().getContent());
			String htmlCharset = this.getHtmlCharset(httpResponse, contentBytes);
			if(htmlCharset != null) {
				return new String(contentBytes, htmlCharset);
			} else {
				this.logger.warn("Charset autodetect failed, use {} as charset. Please specify charset in Site.setCharset()", Charset.defaultCharset());
				return new String(contentBytes);
			}
		} else {
			return IOUtils.toString(httpResponse.getEntity().getContent(), charset);
		}
	}

	protected String getHtmlCharset(HttpResponse httpResponse, byte[] contentBytes) throws IOException {
		String value = httpResponse.getEntity().getContentType().getValue();
		String charset = UrlUtils.getCharset(value);
		if(StringUtils.isNotBlank(charset)) {
			this.logger.debug("Auto get charset: {}", charset);
			return charset;
		} else {
			Charset defaultCharset = Charset.defaultCharset();
			String content = new String(contentBytes, defaultCharset.name());
			if(StringUtils.isNotEmpty(content)) {
				Document document = Jsoup.parse(content);
				Elements links = document.select("meta");
				Iterator var9 = links.iterator();

				while(var9.hasNext()) {
					Element link = (Element)var9.next();
					String metaContent = link.attr("content");
					String metaCharset = link.attr("charset");
					if(metaContent.indexOf("charset") != -1) {
						metaContent = metaContent.substring(metaContent.indexOf("charset"), metaContent.length());
						charset = metaContent.split("=")[1];
						break;
					}

					if(StringUtils.isNotEmpty(metaCharset)) {
						charset = metaCharset;
						break;
					}
				}
			}

			this.logger.debug("Auto get charset: {}", charset);
			return charset;
		}
	}
}
