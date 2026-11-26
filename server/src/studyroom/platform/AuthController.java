package studyroom.platform;

import java.util.Map;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** Session endpoints: health probe, CSRF token, login, current user. */
@RestController
@RequestMapping("/api")
public class AuthController {
    private final IdentityService identity;
    private final AuthenticationManager manager;
    private final HttpSessionSecurityContextRepository contexts;

    public AuthController(IdentityService identity, AuthenticationManager manager,
                          HttpSessionSecurityContextRepository contexts) {
        this.identity = identity;
        this.manager = manager;
        this.contexts = contexts;
    }

    /** Liveness probe used by the start script and the snapshot test. */
    @GetMapping("/health")
    Map<String,String> health() {
        return Map.of("status", "UP");
    }

    /** Hands the browser the CSRF token required by every state-changing call. */
    @GetMapping("/auth/csrf")
    Map<String,String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    /** Authenticates the credentials, rotates the session id, and returns the account. */
    @PostMapping("/auth/login")
    Map<String,Object> login(@Valid @RequestBody LoginRequest input,
                             HttpServletRequest request, HttpServletResponse response) {
        try {
            var auth = manager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(input.username(), input.password()));
            request.getSession(true);
            request.changeSessionId();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            return identity.current();
        } catch (AuthenticationException ex) {
            throw new BusinessException(401, "用户名或密码错误，或账号已停用");
        }
    }

    /** Returns the account shown in the page header. */
    @GetMapping("/auth/me")
    Map<String,Object> me() {
        return identity.current();
    }
}
