package ddlx.api;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Log;
/**
 * 图像文件管理器，负责处理图像文件的存储、缓存和哈希映射
 */
public class ImageFileManager {
    private static final String TAG = "ImageFileManager";
    private static ImageFileManager instance;
    private final Context context;
    private final Map<String, String> hashToPathMap = new HashMap<>();
    private final String cacheDir;
    
    private ImageFileManager(Context context) {
        this.context = context.getApplicationContext();
        this.cacheDir = context.getExternalFilesDir(null).getAbsolutePath() + "/image_cache";
        ensureCacheDirExists();
    }
    
    public static synchronized ImageFileManager getInstance(Context context) {
        if (instance == null) {
            instance = new ImageFileManager(context);
        }
        return instance;
    }
    
    private void ensureCacheDirExists() {
        File dir = new File(cacheDir);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e(TAG, "Failed to create cache directory: " + cacheDir);
            }
        }
    }
    
    /**
     * 处理Base64编码的图像数据，保存为文件并返回文件路径
     * 
     * @param base64Data Base64编码的图像数据
     * @return 保存的图像文件路径
     */
    public String processBase64Image(String base64Data) {
        if (context == null) {
            Log.e(TAG, "Context not initialized, cannot process image");
            return null;
        }
        try {
            Log.d(TAG, "Starting to process base64 image data, length: " + base64Data.length());
            
            // 增加对 Base64 数据的初步校验
            if (base64Data == null || base64Data.isEmpty()) {
                Log.e(TAG, "Invalid base64 data: null or empty");
                return null;
            }
            
            // 计算SHA-256哈希值
            String hash = calculateSHA256(base64Data);
            Log.d(TAG, "Calculated image hash: " + hash);
            
            // 检查是否已缓存
            if (hashToPathMap.containsKey(hash)) {
                String cachedPath = hashToPathMap.get(hash);
                File cachedFile = new File(cachedPath);
                if (cachedFile.exists()) {
                    Log.d(TAG, "Found cached image: " + cachedPath);
                    return cachedPath;
                } else {
                    Log.w(TAG, "Cached file not found on disk: " + cachedPath);
                }
            }
            
            // 解码Base64数据
            Log.d(TAG, "Decoding base64 data");
            byte[] imageData;
            try {
                imageData = Base64.getDecoder().decode(base64Data);
                Log.d(TAG, "Successfully decoded base64 data, byte length: " + imageData.length);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to decode base64 data: " + e.getMessage());
                return null;
            }
            
            // 创建图像文件
            String fileName = "img_" + hash.substring(0, 8) + ".jpg";
            String filePath = cacheDir + "/" + fileName;
            Log.d(TAG, "Creating image file: " + filePath);
            File imageFile = new File(filePath);
            
            // 保存图像文件
            try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                fos.write(imageData);
                fos.flush();
                Log.d(TAG, "Successfully saved image file");
            } catch (Exception e) {
                Log.e(TAG, "Failed to save image file: " + e.getMessage());
                return null;
            }
            
            // 验证图像文件是否有效
            if (isValidImageFile(filePath)) {
                // 添加到缓存映射
                hashToPathMap.put(hash, filePath);
                Log.d(TAG, "Saved new image: " + filePath);
                return filePath;
            } else {
                Log.e(TAG, "Invalid image data");
                imageFile.delete();
                return null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing base64 image: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 验证图像文件是否有效
     */
    private boolean isValidImageFile(String filePath) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, options);
            return options.outWidth > 0 && options.outHeight > 0;
        } catch (Exception e) {
            Log.e(TAG, "Error validating image: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 计算字符串的SHA-256哈希值
     */
    private String calculateSHA256(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes());
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
    
    /**
     * 清理过期的缓存文件
     */
    public void cleanupCache() {
        File dir = new File(cacheDir);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                long now = System.currentTimeMillis();
                long maxAge = 24 * 60 * 60 * 1000; // 24小时
                
                for (File file : files) {
                    if (now - file.lastModified() > maxAge) {
                        if (file.delete()) {
                            Log.d(TAG, "Deleted expired cache file: " + file.getName());
                            // 从映射中移除
                            for (Map.Entry<String, String> entry : hashToPathMap.entrySet()) {
                                if (entry.getValue().equals(file.getAbsolutePath())) {
                                    hashToPathMap.remove(entry.getKey());
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}