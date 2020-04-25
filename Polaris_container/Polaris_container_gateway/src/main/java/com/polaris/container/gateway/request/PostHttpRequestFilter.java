package com.polaris.container.gateway.request;

import java.net.URLDecoder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.polaris.container.gateway.GatewayConstant;
import com.polaris.container.gateway.pojo.HttpFilterFile;
import com.polaris.container.gateway.util.RequestUtil;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;

/**
 * @author:Tom.Yu
 *
 * Description:
 *
 */
public class PostHttpRequestFilter extends HttpRequestFilter {
	private static Logger logger = LoggerFactory.getLogger(PostHttpRequestFilter.class);
    private static Pattern filePattern = Pattern.compile("Content-Disposition: form-data;(.+)filename=\"(.+)\\.(.*)\"");
    private Set<Pattern> patterns0 = new HashSet<>();
    private Set<Pattern> patterns1 = new HashSet<>();

	@Override
	public void onChange(HttpFilterFile file) {
		
		//0-file
		if (file == httpFilterEntity.getFiles()[0]) {
			Set<String> data = file.getData();
			Set<Pattern> tempPatterns = new HashSet<>();
			if (data != null) {
				for (String conf : data) {
					tempPatterns.add(Pattern.compile(conf));
				}
			}
			patterns0 = tempPatterns;
			
		} else {
			//1-file
			Set<String> data = file.getData();
			Set<Pattern> tempPatterns = new HashSet<>();
			if (data != null) {
				for (String conf : data) {
					tempPatterns.add(Pattern.compile(conf));
				}
			}
			patterns1 = tempPatterns;
		}
		
		
	}
    @Override
    public boolean doFilter(HttpRequest originalRequest, HttpObject httpObject, ChannelHandlerContext ctx) {
        if (originalRequest.method().name().equals("POST")) {
            if (httpObject instanceof HttpContent) {
                HttpContent httpContent = (HttpContent) httpObject;
                String contentBody = null;
                List<String> headerValues = GatewayConstant.getHeaderValues(originalRequest, "Content-Type");
                if (headerValues.size() > 0 && headerValues.get(0) != null) {
                    if (GatewayConstant.getHeaderValues(originalRequest, "Content-Type") != null && headerValues.get(0).startsWith("multipart/form-data")) {
                        contentBody = new String(Unpooled.copiedBuffer(httpContent.content()).array());
                    } else {
                        try {
                            String contentStr = new String(Unpooled.copiedBuffer(httpContent.content()).array()).replaceAll("%", "%25");
                            contentBody = URLDecoder.decode(contentStr, "UTF-8");
                        } catch (Exception e) {
                            logger.warn("URL:{} POST body is inconsistent with the rules", originalRequest.uri(), e);
                        }
                    }

                    if (contentBody != null) {
                    	String[] kv = contentBody.split("=");
                        if (kv.length == 2) {
                        	RequestUtil.setPostParameter(kv[0].trim(), kv[1].trim());
                        }
                        for (Pattern pattern : patterns0) {
                            Matcher matcher = pattern.matcher(contentBody.toLowerCase());
                            if (matcher.find()) {
                                hackLog(logger, GatewayConstant.getRealIp(originalRequest), PostHttpRequestFilter.class.getSimpleName(), pattern.toString());
                                return true;
                            }
                        }
                        Matcher fileMatcher = filePattern.matcher(contentBody);
                        if (fileMatcher.find()) {
                            String fileExt = fileMatcher.group(3);
                            for (Pattern pat : patterns1) {
                                if (pat.matcher(fileExt).matches()) {
                                    hackLog(logger, GatewayConstant.getRealIp(originalRequest), PostHttpRequestFilter.class.getSimpleName(), filePattern.toString());
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
