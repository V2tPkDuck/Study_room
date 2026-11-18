package studyroom;

import java.util.Map;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final Accounts accounts;
    private final AuthenticationManager manager;
    private final HttpSessionSecurityContextRepository contexts;

    public AuthController(Accounts accounts, AuthenticationManager manager,
                          HttpSessionSecurityContextRepository contexts) {
        this.accounts = accounts;
        this.manager = manager;
        this.contexts = contexts;
    }

    @GetMapping("/health")
    Map<String,String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/auth/csrf")
    Map<String,String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/auth/login")
    Map<String,Object> login(@Valid @RequestBody Requests.Login input,
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
            return accounts.current();
        } catch (AuthenticationException ex) {
            throw new BusinessException(401, "用户名或密码错误，或账号已停用");
        }
    }

    @GetMapping("/auth/me")
    Map<String,Object> me() {
        return accounts.current();
    }
}
