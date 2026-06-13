package cncode.provider;

import java.net.URI;

public final class ProviderHttpUtil {
    private ProviderHttpUtil() {
    }

    public static URI endpoint(String baseUrl, String path) throws ProviderException {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        try {
            return URI.create(normalizedBase + normalizedPath);
        } catch (IllegalArgumentException error) {
            throw new ProviderException("base_url 不是合法地址：" + baseUrl, error);
        }
    }
}
