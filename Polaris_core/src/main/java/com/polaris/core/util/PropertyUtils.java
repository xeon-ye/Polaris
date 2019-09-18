package com.polaris.core.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.polaris.core.Constant;

public class PropertyUtils {

	private PropertyUtils() {
	}
	
    /** 
     * 判断文件是否存在. 
     * @param fileDir  文件路径 
     * @return 
     */  
    public static String getFullPath(String fileDir) throws IOException {  
    	ClassLoader classLoader = PropertyUtils.class.getClassLoader();
        URL url = classLoader.getResource("");
        return java.net.URLDecoder.decode(url.getPath(),"utf-8") + File.separator + fileDir;
    }
    
	/**  
	  * 根据Key 读取Value  
	  *   
	  * @param key  
	  * @return  
	  */ 
	@SuppressWarnings("unchecked")
	public static String readData(String propertyName, String key) throws IOException{
		
		   //创建文件夹
		   try (InputStream in = getStream(propertyName)) {
				if (in == null) {
					return null;
				}
	            Properties config = new Properties();
	            config.load(in);
	            Object result = config.getProperty(key);
	 		   if(result == null) {
	 			   return null;
	 		   } else {
	 			   if (result instanceof ArrayList) {
	 				   StringBuilder strB = new StringBuilder();
	 				   for (String temp : ((List<String>)result)) {
	 					   if (StringUtil.isEmpty(strB.toString())) {
	 						   strB.append(temp);
	 					   } else {
	 						   strB.append(",");
	 						   strB.append(temp);
	 					   }
	 				   }
	 				   return strB.toString();
	 			   }
	 			   return result.toString();
	 		   }
	      } catch (IOException e) {
	      	e.printStackTrace();
	      }
		  return "";
   }

	@SuppressWarnings("rawtypes")
	public static String getPropertiesFileContent(String fileName) {
		StringBuffer buffer = new StringBuffer();
		try (InputStream in = getStream(fileName)) {
			if (in == null) {
				return null;
			}
          Properties p = new Properties();
          p.load(in);
          for (Map.Entry entry : p.entrySet()) {
              String key = (String) entry.getKey();
              buffer.append(key + "=" + entry.getValue());
              buffer.append(Constant.LINE_SEP);
          }
      } catch (IOException e) {
      	e.printStackTrace();
      }
	  return buffer.toString();
	}
	
	public static InputStream getStream(String fileName) throws IOException {
		
		//先判断目录下的文件夹
		File file = new File(PropertyUtils.getFullPath(Constant.CONFIG + File.separator + fileName));
		if (file.exists()) {
			return new FileInputStream(file);
		}
		
		//更目录下
		file = new File(PropertyUtils.getFullPath(fileName));
		if (file.exists()) {
			return new FileInputStream(file);
		}
		
		//classpath:config/filename
		InputStream inputStream = PropertyUtils.class.getClassLoader().getResourceAsStream(Constant.CONFIG + File.separator + fileName);
		if (inputStream != null) {
			return inputStream;
		}
		
		//classpath:filename
		return PropertyUtils.class.getClassLoader().getResourceAsStream(fileName);
	}
	
	public static File getFileNotInJar(String fileName)  {
		try {
			File file = new File(PropertyUtils.getFullPath(Constant.CONFIG + File.separator + fileName));
			if (file.exists()) {
				return file;
			}
			file = new File(PropertyUtils.getFullPath(fileName));
			if (file.exists()) {
				return file;
			}
		} catch (IOException ex) {
			//ingore
		}
		return null;
	}
}
