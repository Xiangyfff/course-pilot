package com.courseplanner.auth;

import com.courseplanner.common.ApiResponse;
import com.courseplanner.common.BizException;
import com.courseplanner.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record UserView(long id, String username, String displayName) {
    }

    public record LoginResponse(String token, UserView user) {
    }

    private final UserRepository users;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder;

    public AuthController(UserRepository users, JwtService jwtService, BCryptPasswordEncoder encoder) {
        this.users = users;
        this.jwtService = jwtService;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        UserRepository.UserRow user = users.findByUsername(req.username().trim())
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        if (!encoder.matches(req.password(), user.passwordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return ApiResponse.ok(toResponse(user));
    }

    /** 演示入口：一条命令进系统，演示时不用现场敲密码。 */
    @PostMapping("/demo")
    public ApiResponse<LoginResponse> demo() {
        UserRepository.UserRow user = users.findByUsername("demo")
                .orElseThrow(() -> BizException.notFound("演示账号"));
        return ApiResponse.ok(toResponse(user));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(@Authenticated long userId) {
        UserRepository.UserRow user = users.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "账号不存在"));
        return ApiResponse.ok(new UserView(user.id(), user.username(), user.displayName()));
    }

    private LoginResponse toResponse(UserRepository.UserRow user) {
        String token = jwtService.issue(user.id(), user.username());
        return new LoginResponse(token, new UserView(user.id(), user.username(), user.displayName()));
    }
}
