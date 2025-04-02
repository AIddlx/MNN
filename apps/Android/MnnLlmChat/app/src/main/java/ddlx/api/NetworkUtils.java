package ddlx.api;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

import android.util.Log;
import java.net.InetAddress;
import java.net.Inet4Address;

public class NetworkUtils {
    private static final String TAG = "NetworkUtils";

    /**
     * 从URL下载图片并转换为Base64编码
     * 
     * @param imageUrl 图片URL
     * @return Base64编码的图片数据
     */
    public static String downloadImageAsBase64(String imageUrl) throws Exception {
        java.net.URL url = new java.net.URL(imageUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new Exception("Failed to download image: " + connection.getResponseMessage());
            }
            
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream inputStream = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
            }
            
            byte[] imageData = output.toByteArray();
            return android.util.Base64.encodeToString(imageData, android.util.Base64.DEFAULT);
            
        } finally {
            connection.disconnect();
        }
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            // 首选 WLAN 接口
            for (NetworkInterface ni : interfaces) {
                if (ni.getName().toLowerCase().contains("wlan") || ni.getName().toLowerCase().contains("eth")) {
                    List<InetAddress> addresses = Collections.list(ni.getInetAddresses());
                    for (InetAddress address : addresses) {
                        if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                            String ip = address.getHostAddress();
                            Log.i(TAG, "Using network interface: " + ni.getName() + ", IP: " + ip);
                            return ip;
                        }
                    }
                }
            }
            // 如果没有找到 WLAN，则使用任何可用的网络接口
            for (NetworkInterface ni : interfaces) {
                if (!ni.isLoopback() && ni.isUp()) {
                    List<InetAddress> addresses = Collections.list(ni.getInetAddresses());
                    for (InetAddress address : addresses) {
                        if (address instanceof Inet4Address) {
                            String ip = address.getHostAddress();
                            Log.i(TAG, "Fallback to network interface: " + ni.getName() + ", IP: " + ip);
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP address: " + e.getMessage());
        }
        Log.w(TAG, "No suitable network interface found, using localhost");
        return "127.0.0.1";
    }
}