import org.apereo.cas.support.pac4j.web.DelegatedAuthenticationRequestCustomizer;
import org.pac4j.core.client.BaseClient;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

public class LoginHintCustomizer implements DelegatedAuthenticationRequestCustomizer {
    @Override
    public void customize(final HttpServletRequest request, 
                          final BaseClient client, 
                          final Map<String, String> params) {
        String loginHint = getCookieValue(request, "emailCookie"); // replace 'emailCookie' with your cookie's name
        if (loginHint != null) {
            params.put("login_hint", loginHint);
        }
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
