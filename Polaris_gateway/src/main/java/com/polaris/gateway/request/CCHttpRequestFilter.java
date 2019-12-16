package com.polaris.gateway.request;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.adapter.servlet.callback.UrlCleaner;
import com.alibaba.csp.sentinel.adapter.servlet.callback.WebCallbackManager;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.RateLimiter;
import com.polaris.cache.Cache;
import com.polaris.cache.CacheFactory;
import com.polaris.core.Constant;
import com.polaris.core.config.ConfClient;
import com.polaris.core.config.ConfHandlerSupport;
import com.polaris.core.config.ConfListener;
import com.polaris.core.dto.ResultDto;
import com.polaris.core.util.StringUtil;
import com.polaris.core.util.UuidUtil;
import com.polaris.gateway.GatewayConstant;
import com.polaris.gateway.support.HttpRequestFilterSupport;

import cn.hutool.core.io.FileUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;

/**
 * @author:Tom.Yu
 *
 * Description:
 * cc拦截
 */
/**
 * @author:Tom.Yu
 *
 * Description:
 * cc拦截
 */
@Service
public class CCHttpRequestFilter extends HttpRequestFilter {
	private static Logger logger = LoggerFactory.getLogger(CCHttpRequestFilter.class);
	private final static String FILE_NAME = "cc.txt";
	
    //控制总的流量
	private static volatile RateLimiter totalRateLimiter;
	private static volatile int int_all_rate = 0;
	private static volatile int int_all_timeout=30;//最大等待30秒返回
	
	//ip维度，每秒钟的访问数量
	private static volatile LoadingCache<String, AtomicInteger> secIploadingCache;
	private static volatile LoadingCache<String, AtomicInteger> minIploadingCache;
	private static volatile int[] int_ip_rate = {10,60};
	
	//被禁止的IP是否要持久化磁盘 
	private static volatile Cache blackIpCache = CacheFactory.getCache("cc.black.ip");//被禁止的ip
	private static volatile Integer blockSeconds = 60;
	private static volatile boolean blockIpPersistent = false;
	private static volatile String blockIpSavePath = "";
	private static volatile int timerinterval = 0;//每间隔600秒执行一次
	private static volatile Timer timer = null;
	
	static {
		
		//IP维度，每秒钟的访问数量
		secIploadingCache = CacheBuilder.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(1, TimeUnit.SECONDS)
                .build(new CacheLoader<String, AtomicInteger>() {
                    @Override
                    public AtomicInteger load(String key) throws Exception {
                    	return new AtomicInteger(0);
                    }
                });
		
		//IP维度，每秒钟的访问数量
		minIploadingCache = CacheBuilder.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build(new CacheLoader<String, AtomicInteger>() {
                    @Override
                    public AtomicInteger load(String key) throws Exception {
                    	return new AtomicInteger(0);
                    }
                });

		//先获取
		loadFile(ConfClient.getConfigValue(FILE_NAME));
		
		//后监听
    	ConfClient.addListener(FILE_NAME, new ConfListener() {
			@Override
			public void receive(String content) {
				loadFile(content);
			}
    	});
    }
    
    private static void loadFile(String content) {
    	String[] contents = content.split(Constant.LINE_SEP);
    	int blockSecondsTemp = 60;
    	int[] IP_RATE = {10,60};
    	int ALL_RATE = 300;
    	int int_all_timeout_temp = 30;
    	int timerintervalTemp = 600;
    	boolean blockIpPersistentTemp = false;
    	String blockIpSavePathTemp = null;
    	for (String conf : contents) {
    		if (StringUtil.isNotEmpty(conf) && !conf.startsWith("#")) {
    			conf = conf.replace("\n", "");
    			conf = conf.replace("\r", "");

				String[] kv = ConfHandlerSupport.getKeyValue(conf);
    			// ip.rate
    			if (kv[0].equals("cc.ip.rate")) {
    				try {
    					String[] rates = kv[1].split(",");
    					if (rates.length == 1) {
    						IP_RATE = new int[]{Integer.parseInt(rates[0]),60};
    					} else {
    						IP_RATE = new int[]{Integer.parseInt(rates[0]),Integer.parseInt(rates[1])};
    					}
    				} catch (Exception ex) {
    				}
    			}
    			// all.rate
    			if (kv[0].equals("cc.all.rate")) {
    				try {
    					ALL_RATE = Integer.parseInt(kv[1]);
    				} catch (Exception ex) {
    				}
    			}
    			// all.rate
    			if (kv[0].equals("cc.all.timeout")) {
    				try {
    					int_all_timeout_temp = Integer.parseInt(kv[1]);
    				} catch (Exception ex) {
    				}
    			}

    			
    			// 被禁止IP的时间-seconds
    			if (kv[0].equals("cc.ip.block.seconds")) {
    				try {
        				blockSecondsTemp = Integer.parseInt(kv[1]);
    				} catch (Exception ex) {
    				}
    			}
    			
    			// 被禁止IP的是否持久化
    			if (kv[0].equals("cc.ip.persistent")) {
    				try {
    					blockIpPersistentTemp = Boolean.parseBoolean(kv[1]);
    				} catch (Exception ex) {
    				}
    			}
    			
    			// 被禁止间隔时间秒
    			if (kv[0].equals("cc.ip.persistent.interval")) {
    				try {
    					timerintervalTemp = Integer.parseInt(kv[1]);
    				} catch (Exception ex) {
    				}
    			}
    			
    			// 持久化地址
    			if (kv[0].equals("cc.ip.persistent.path")) {
					blockIpSavePathTemp = kv[1];
    			}

    		}
    	}
    	
    	//总访问量
    	if (int_all_rate != ALL_RATE) {
    		int_all_rate = ALL_RATE;
    		totalRateLimiter = RateLimiter.create(int_all_rate);//控制总访问量
    	}
    	int_all_timeout = int_all_timeout_temp;
		
		//IP地址维度的
		int_ip_rate = IP_RATE;//单个IP的访问访问量
		
		//被限制IP的访问时间
        blockSeconds = blockSecondsTemp;
        blockIpPersistent = blockIpPersistentTemp;
        blockIpSavePath = blockIpSavePathTemp;
        
		//执行频次
        if (blockIpPersistent && StringUtil.isNotEmpty(blockIpSavePath)) {
            if (timerinterval != timerintervalTemp || !blockIpSavePathTemp.equals(blockIpSavePath)) {
         		FileUtil.mkdir(blockIpSavePath);
            	if (timer != null) {
            		timer.cancel();
            		timer = null;
            	}
            	timerinterval = timerintervalTemp;
            	long period = timerinterval*1000;
            	timer = new Timer();
            	timer.scheduleAtFixedRate(new BusinessTask(), period, period);
            }
        } else {
        	if (timer != null) {
        		timer.cancel();
        		timer = null;
        	}
        }
    }
    
    private static class BusinessTask extends TimerTask{
		@Override
        public void run() {
         	try {
         		@SuppressWarnings("unchecked")
				List<String> keys = blackIpCache.getKeys();
         		if (keys != null && keys.size() > 0) {
             		String path = blockIpSavePath + File.separator + UuidUtil.generateUuid();
             		FileUtil.appendLines(keys, path, Charset.defaultCharset().toString());
         		}
			} catch (Exception e) {
				logger.error("ERROR",e);
			}
        }
    }

	@Override
    public boolean doFilter(HttpRequest originalRequest, HttpObject httpObject, ChannelHandlerContext channelHandlerContext) {
        if (httpObject instanceof HttpRequest) {
            logger.debug("filter:{}", this.getClass().getName());
            String realIp = GatewayConstant.getRealIp((DefaultHttpRequest) httpObject);
            
        	//是否黑名单
        	if (blackIpCache.get(realIp) != null){
        		String message = realIp + " access has exceeded ";
            	this.setResultDto(HttpRequestFilterSupport.createResultDto(Constant.RESULT_FAIL,message));
                hackLog(logger, realIp, "cc", message);
        		return true;
        	}
            
            //控制总流量，超标直接返回
            HttpRequest httpRequest = (HttpRequest)httpObject;
            String url = getUrl(httpRequest);
            
            //获取cc宜兰
            if (url.equals("/cc/ip")) {
            	@SuppressWarnings("rawtypes")
				ResultDto<List> dto = new ResultDto<>();
            	dto.setCode(Constant.RESULT_SUCCESS);
            	dto.setData(blackIpCache.getKeys());
            	this.setResultDto(dto);
            	return true;
            }
            
            //cc攻击
            if (ccHack(url, realIp)) {
            	String message = httpRequest.uri() + " " + realIp + " access  has exceeded ";
            	this.setResultDto(HttpRequestFilterSupport.createResultDto(Constant.RESULT_FAIL,message));
                hackLog(logger, realIp, "cc", message);
            	return true;
            }
            
            //对各个URL资源进行熔断拦截
            if (doSentinel(url)) {
            	String message = httpRequest.uri() + " access  has exceeded ";
            	this.setResultDto(HttpRequestFilterSupport.createResultDto(Constant.RESULT_FAIL,message));
                hackLog(logger, realIp, "cc", message);
            	return true;
            }
            
        }
        return false;
    }
	
	//目标拦截
    private boolean doSentinel(String url) {
    	Entry entry = null;
    	try {
            UrlCleaner urlCleaner = WebCallbackManager.getUrlCleaner();
            if (urlCleaner != null) {
            	url = urlCleaner.clean(url);
            }
            ContextUtil.enter(url);
            SphU.entry(url, EntryType.IN);
            return false;
        } catch (BlockException e) {
        	return true;
        } finally {
        	if (entry != null) {
                entry.exit();
            }
            ContextUtil.exit();
        }
    }
    
    private String getUrl(HttpRequest httpRequest) {
    	//获取url
        String uri = httpRequest.uri();
        String url;
        int index = uri.indexOf("?");
        if (index > 0) {
            url = uri.substring(0, index);
        } else {
            url = uri;
        }
        return url;
    }
    
    private boolean ccHack(String url, String realIp) {
       	
    	//IP每秒访问
		try {
			AtomicInteger secRateLimiter = (AtomicInteger) secIploadingCache.get(realIp);
	        int count = secRateLimiter.incrementAndGet();
	        if (count > int_ip_rate[0]) {
	    		blackIpCache.put(realIp, new Object(),blockSeconds);//拒绝
	    		return true;//拒绝
	        } 
		} catch (ExecutionException e) {
			logger.error(e.getMessage());
        	return true;
		}
		
		//IP每分访问
		try {
	        AtomicInteger minRateLimiter = (AtomicInteger) minIploadingCache.get(realIp);
	        int count = minRateLimiter.incrementAndGet();
	        if (count > int_ip_rate[1]) {
	    		blackIpCache.put(realIp, new Object(),blockSeconds);//拒绝
	    		return true;//拒绝
	        } 
		} catch (ExecutionException e) {
			logger.error(e.getMessage());
        	return true;
		}
		
        //总量控制
        if (totalRateLimiter.tryAcquire(1, int_all_timeout, TimeUnit.SECONDS)) {
            return true;
        }

        return false;
    }
    
}


