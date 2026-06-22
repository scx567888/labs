package dev.scx.ffi.x.win32.helper;


import dev.scx.ffi.x.win32.Win32Exception;

import java.util.Map;

import static dev.scx.ffi.x.win32.WinReg.HKEY_CURRENT_USER;
import static dev.scx.ffi.x.win32.helper.Advapi32Helper.*;


/// 设置 windows 系统的代理 (使用前请检查当前操作系统是否为 Windows !!!)
///
/// @author scx567888
/// @version 0.0.1
/// @see <a href="https://learn.microsoft.com/zh-cn/windows/win32/wininet/enabling-internet-functionality">https://learn.microsoft.com/zh-cn/windows/win32/wininet/enabling-internet-functionality</a>
public final class WindowsProxyHelper {

    /// 注册表项地址
    public static final String INTERNET_SETTINGS_KEY_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    public static final String PROXY_ENABLE = "ProxyEnable";
    public static final String PROXY_SERVER = "ProxyServer";
    public static final String PROXY_OVERRIDE = "ProxyOverride";
    public static final String LOCAL_HOST = "127.0.0.1";

    /// 获取代理配置信息
    ///
    /// @return 代理配置信息
    public static Map<String, Object> getInternetSettingsValues() {
        return registryGetValues(
                HKEY_CURRENT_USER,
                INTERNET_SETTINGS_KEY_PATH
        );
    }

    public static Boolean getProxyEnableOrNull() {
        try {
            return getProxyEnable();
        } catch (Win32Exception e) {
            return null;
        }
    }

    public static Boolean getProxyEnable() {
        var value = registryGetIntValue(
                HKEY_CURRENT_USER,
                INTERNET_SETTINGS_KEY_PATH,
                PROXY_ENABLE
        );
        return value == 1;
    }

    public static void setProxyEnabled(boolean enable) {
        registrySetIntValue(
                HKEY_CURRENT_USER,
                INTERNET_SETTINGS_KEY_PATH,
                PROXY_ENABLE,
                enable ? 1 : 0
        );
    }

    /// 开启 代理
    public static void enableProxy() {
        setProxyEnabled(true);
    }

    /// 关闭 代理设置
    public static void disableProxy() {
        setProxyEnabled(false);
    }

    public static String getProxyServerOrNull() {
        try {
            return getProxyServer();
        } catch (Win32Exception e) {
            return null;
        }
    }

    public static String getProxyServer() {
        return registryGetStringValue(
                HKEY_CURRENT_USER,
                INTERNET_SETTINGS_KEY_PATH,
                PROXY_SERVER
        );
    }

    /// 设置代理服务器 (主机默认为本机)
    ///
    /// @param port 端口
    public static void setProxyServer(int port) {
        setProxyServer(LOCAL_HOST + ":" + port);
    }

    /// 设置代理服务器
    ///
    /// @param host 主机
    public static void setProxyServer(String host) {
        registrySetStringValue(
                HKEY_CURRENT_USER,
                INTERNET_SETTINGS_KEY_PATH,
                PROXY_SERVER,
                host
        );
    }

    /// 清空 代理设置
    public static void clearProxyServer() {
        setProxyServer("");
    }

    public static String[] getProxyOverrideOrNull() {
        try {
            return getProxyOverride();
        } catch (Win32Exception e) {
            return null;
        }
    }

    public static String[] getProxyOverride() {
        var value = registryGetStringValue(
                HKEY_CURRENT_USER,
                INTERNET_SETTINGS_KEY_PATH,
                PROXY_OVERRIDE
        );
        return value.split(";");
    }

    /// 设置绕过代理的 主机 列表
    public static void setProxyOverride(String... list) {
        registrySetStringValue(
                HKEY_CURRENT_USER,
                INTERNET_SETTINGS_KEY_PATH,
                PROXY_OVERRIDE,
                String.join(";", list)
        );
    }

    /// 清空 代理设置
    public static void clearProxyOverride() {
        setProxyOverride();
    }

    public static ProxyInfo getProxyInfo() {
        return new ProxyInfo(getProxyServer(), getProxyEnable(), getProxyOverride());
    }

    public static ProxyInfo getProxyInfoOrNull() {
        return new ProxyInfo(getProxyServerOrNull(), getProxyEnableOrNull(), getProxyOverrideOrNull());
    }

    public static void setProxy(ProxyInfo proxyInfo) {
        if (proxyInfo.proxyServer != null) {
            setProxyServer(proxyInfo.proxyServer);
        }
        if (proxyInfo.proxyEnable != null) {
            setProxyEnabled(proxyInfo.proxyEnable);
        }
        if (proxyInfo.proxyOverride != null) {
            setProxyOverride(proxyInfo.proxyOverride);
        }
    }

    public record ProxyInfo(String proxyServer, Boolean proxyEnable, String[] proxyOverride) {

    }

}
