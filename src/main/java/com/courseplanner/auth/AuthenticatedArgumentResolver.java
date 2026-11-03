package com.courseplanner.auth;

import com.courseplanner.common.BizException;
import com.courseplanner.common.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class AuthenticatedArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(Authenticated.class)) {
            return false;
        }
        // 注意：Long.class.isAssignableFrom(long.class) 是 false，
        // 这里必须显式判断基本类型，否则参数会漏给默认解析器并报
        // "Optional long parameter 'userId' is present but cannot be translated..."。
        Class<?> type = parameter.getParameterType();
        return type == Long.class || type == long.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object v = webRequest.getAttribute(AuthInterceptor.ATTR_USER_ID, NativeWebRequest.SCOPE_REQUEST);
        if (v == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return v;
    }
}
