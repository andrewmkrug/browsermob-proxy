package net.lightbody.bmp.proxy;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import net.lightbody.bmp.BrowserMobProxy;
import net.lightbody.bmp.BrowserMobProxyServer;
import net.lightbody.bmp.filters.RequestFilter;
import net.lightbody.bmp.filters.ResponseFilter;
import net.lightbody.bmp.proxy.test.util.MockServerTest;
import net.lightbody.bmp.proxy.test.util.ProxyServerTest;
import net.lightbody.bmp.proxy.util.IOUtils;
import net.lightbody.bmp.util.HttpMessageContents;
import net.lightbody.bmp.util.HttpObjectUtil;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.After;
import org.junit.Test;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.mockserver.matchers.Times;
import org.mockserver.model.Header;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class InterceptorTest extends MockServerTest {
    private BrowserMobProxy proxy;

    @After
    public void tearDown() {
        if (proxy != null && proxy.isStarted()) {
            proxy.abort();
        }
    }

    @Test
    public void testCanShortCircuitResponse() throws IOException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/regular200"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success"));

        // this response should be "short-circuited" by the interceptor
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/shortcircuit204"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success"));

        proxy = new BrowserMobProxyServer();
        proxy.start();

        final AtomicBoolean interceptorFired = new AtomicBoolean(false);
        final AtomicBoolean shortCircuitFired= new AtomicBoolean(false);

        proxy.addFirstHttpFilterFactory(new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                        if (httpObject instanceof HttpRequest) {
                            interceptorFired.set(true);

                            HttpRequest httpRequest = (HttpRequest) httpObject;

                            if (httpRequest.getMethod().equals(HttpMethod.GET) && httpRequest.getUri().contains("/shortcircuit204")) {
                                HttpResponse httpResponse = new DefaultHttpResponse(httpRequest.getProtocolVersion(), HttpResponseStatus.NO_CONTENT);

                                shortCircuitFired.set(true);

                                return httpResponse;
                            }
                        }

                        return super.clientToProxyRequest(httpObject);
                    }
                };
            }
        });

        try (CloseableHttpClient httpClient = ProxyServerTest.getNewHttpClient(proxy.getPort())) {
            CloseableHttpResponse response = httpClient.execute(new HttpGet("http://localhost:" + mockServerPort + "/regular200"));
            String responseBody = IOUtils.toStringAndClose(response.getEntity().getContent());

            assertTrue("Expected interceptor to fire", interceptorFired.get());
            assertFalse("Did not expected short circuit interceptor code to execute", shortCircuitFired.get());

            assertEquals("Expected server to return a 200", 200, response.getStatusLine().getStatusCode());
            assertEquals("Did not receive expected response from mock server", "success", responseBody);
        };

        interceptorFired.set(false);

        try (CloseableHttpClient httpClient = ProxyServerTest.getNewHttpClient(proxy.getPort())) {
            CloseableHttpResponse response = httpClient.execute(new HttpGet("http://localhost:" + mockServerPort + "/shortcircuit204"));

            assertTrue("Expected interceptor to fire", interceptorFired.get());
            assertTrue("Expected interceptor to short-circuit response", shortCircuitFired.get());

            assertEquals("Expected interceptor to return a 204 (No Content)", 204, response.getStatusLine().getStatusCode());
            assertNull("Expected no entity attached to response", response.getEntity());
        };
    }

    @Test
    public void testCanModifyResponseBodyLarger() throws IOException {
        final String originalText = "The quick brown fox jumps over the lazy dog";
        final String newText = "The quick brown frog jumps over the lazy aardvark";

        testModifiedResponse(originalText, newText);
    }

    @Test
    public void testCanModifyResponseBodySmaller() throws IOException {
        final String originalText = "The quick brown fox jumps over the lazy dog";
        final String newText = "The quick brown fox jumped.";

        testModifiedResponse(originalText, newText);
    }

    @Test
    public void testCanModifyRequest() throws IOException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/modifyrequest"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=utf-8"))
                        .withBody("success"));

        proxy = new BrowserMobProxyServer();
        proxy.start();

        proxy.addFirstHttpFilterFactory(new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                        if (httpObject instanceof HttpRequest) {
                            HttpRequest httpRequest = (HttpRequest) httpObject;
                            httpRequest.setUri(httpRequest.getUri().replace("/originalrequest", "/modifyrequest"));
                        }

                        return super.clientToProxyRequest(httpObject);
                    }
                };
            }
        });

        try (CloseableHttpClient httpClient = ProxyServerTest.getNewHttpClient(proxy.getPort())) {
            CloseableHttpResponse response = httpClient.execute(new HttpGet("http://localhost:" + mockServerPort + "/originalrequest"));
            String responseBody = IOUtils.toStringAndClose(response.getEntity().getContent());

            assertEquals("Expected server to return a 200", 200, response.getStatusLine().getStatusCode());
            assertEquals("Did not receive expected response from mock server", "success", responseBody);
        }
    }

    @Test
    public void testRequestFilterCanModifyRequestBody() throws IOException {
        final String originalText = "original body";
        final String newText = "modified body";

        mockServer.when(request()
                        .withMethod("PUT")
                        .withPath("/modifyrequest")
                        .withBody(newText),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success"));

        proxy = new BrowserMobProxyServer();
        proxy.start();

        proxy.addRequestFilter(new RequestFilter() {
            @Override
            public HttpResponse filterRequest(HttpRequest request, HttpMessageContents contents) {
                if (contents.isText()) {
                    if (contents.getTextContents().equals(originalText)) {
                        contents.setTextContents(newText);
                    }
                }

                return null;
            }
        });

        try (CloseableHttpClient httpClient = ProxyServerTest.getNewHttpClient(proxy.getPort())) {
            HttpPut request = new HttpPut("http://localhost:" + mockServerPort + "/modifyrequest");
            request.setEntity(new StringEntity(originalText));
            CloseableHttpResponse response = httpClient.execute(request);
            String responseBody = IOUtils.toStringAndClose(response.getEntity().getContent());

            assertEquals("Expected server to return a 200", 200, response.getStatusLine().getStatusCode());
            assertEquals("Did not receive expected response from mock server", "success", responseBody);
        }
    }

    @Test
    public void testResponseFilterCanModifyBinaryContents() throws IOException {
        final byte[] originalBytes = new byte[] {1, 2, 3, 4, 5};
        final byte[] newBytes = new byte[] {20, 30, 40, 50, 60};

        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/modifyresponse"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream"))
                        .withBody(originalBytes));

        proxy = new BrowserMobProxyServer();
        proxy.start();

        proxy.addResponseFilter(new ResponseFilter() {
            @Override
            public void filterResponse(HttpResponse response, HttpMessageContents contents) {
                if (!contents.isText()) {
                    if (Arrays.equals(originalBytes, contents.getBinaryContents())) {
                        contents.setBinaryContents(newBytes);
                    }
                }
            }
        });

        try (CloseableHttpClient httpClient = ProxyServerTest.getNewHttpClient(proxy.getPort())) {
            HttpGet request = new HttpGet("http://localhost:" + mockServerPort + "/modifyresponse");
            CloseableHttpResponse response = httpClient.execute(request);
            byte[] responseBytes = org.apache.commons.io.IOUtils.toByteArray(response.getEntity().getContent());

            assertEquals("Expected server to return a 200", 200, response.getStatusLine().getStatusCode());
            assertThat("Did not receive expected response from mock server", responseBytes, equalTo(newBytes));
        }
    }

    @Test
    public void testResponseFilterCanModifyTextContents() throws IOException {
        final String originalText = "The quick brown fox jumps over the lazy dog";
        final String newText = "The quick brown fox jumped.";

        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/modifyresponse"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=utf-8"))
                        .withBody(originalText));

        proxy = new BrowserMobProxyServer();
        proxy.start();

        proxy.addResponseFilter(new ResponseFilter() {
            @Override
            public void filterResponse(HttpResponse response, HttpMessageContents contents) {
                if (contents.isText()) {
                    if (contents.getTextContents().equals(originalText)) {
                        contents.setTextContents(newText);
                    }
                }
            }
        });

        try (CloseableHttpClient httpClient = ProxyServerTest.getNewHttpClient(proxy.getPort())) {
            HttpGet request = new HttpGet("http://localhost:" + mockServerPort + "/modifyresponse");
            request.addHeader("Accept-Encoding", "gzip");
            CloseableHttpResponse response = httpClient.execute(request);
            String responseBody = IOUtils.toStringAndClose(response.getEntity().getContent());

            assertEquals("Expected server to return a 200", 200, response.getStatusLine().getStatusCode());
            assertEquals("Did not receive expected response from mock server", newText, responseBody);
        }
    }

    @Test
    public void testResponseInterceptorWithoutBody() throws IOException {
        mockServer.when(request()
                        .withMethod("HEAD")
                        .withPath("/interceptortest"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")));

        proxy = new BrowserMobProxyServer();
        proxy.start();

        final AtomicReference<byte[]> responseContents = new AtomicReference<>();

        proxy.addResponseFilter(new ResponseFilter() {
            @Override
            public void filterResponse(HttpResponse response, HttpMessageContents contents) {
                responseContents.set(contents.getBinaryContents());
            }
        });

        try (CloseableHttpClient httpClient = ProxyServerTest.getNewHttpClient(proxy.getPort())) {
            CloseableHttpResponse response = httpClient.execute(new HttpHead("http://localhost:" + mockServerPort + "/interceptortest"));

            assertEquals("Expected server to return a 200", 200, response.getStatusLine().getStatusCode());
            assertEquals("Expected binary contents captured in interceptor to be empty", 0, responseContents.get().length);
        }
    }

    /**
     * Helper method for executing response modification tests.
     */
    private void testModifiedResponse(final String originalText, final String newText) throws IOException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/modifyresponse"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=utf-8"))
                        .withBody(originalText));

        proxy = new BrowserMobProxyServer();
        proxy.start();

        proxy.addFirstHttpFilterFactory(new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpObject proxyToClientResponse(HttpObject httpObject) {
                        if (httpObject instanceof FullHttpResponse) {
                            FullHttpResponse httpResponseAndContent = (FullHttpResponse) httpObject;

                            String bodyContent = HttpObjectUtil.extractHttpEntityBody(httpResponseAndContent);

                            if (bodyContent.equals(originalText)) {
                                HttpObjectUtil.replaceTextHttpEntityBody(httpResponseAndContent, newText);
                            }
                        }

                        return super.proxyToClientResponse(httpObject);
                    }
                };
            }

            @Override
            public int getMaximumResponseBufferSizeInBytes() {
                return 10000;
            }
        });

        try (CloseableHttpClient httpClient = ProxyServerTest.getNewHttpClient(proxy.getPort())) {
            CloseableHttpResponse response = httpClient.execute(new HttpGet("http://localhost:" + mockServerPort + "/modifyresponse"));
            String responseBody = IOUtils.toStringAndClose(response.getEntity().getContent());

            assertEquals("Expected server to return a 200", 200, response.getStatusLine().getStatusCode());
            assertEquals("Did not receive expected response from mock server", newText, responseBody);
        }
    }
}
