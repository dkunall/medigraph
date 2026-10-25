package com.medigraph.platform.security;

import com.medigraph.common.security.Actor;
import com.medigraph.common.security.Role;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Builds an {@link Actor} from the verified JWT: {@code sub}, {@code roles}, {@code facility}. Unknown roles are ignored. */
public final class CurrentActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter p) {
        return p.hasParameterAnnotation(CurrentActor.class) && p.getParameterType() == Actor.class;
    }

    @Override
    public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory f) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (!(a instanceof JwtAuthenticationToken token)) {
            throw new AuthenticationCredentialsNotFoundException("no JWT principal");
        }
        return fromJwt(token.getToken());
    }

    public static Actor fromJwt(Jwt jwt) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        List<String> claim = jwt.getClaimAsStringList("roles");
        if (claim != null) {
            for (String s : claim) {
                try {
                    roles.add(Role.valueOf(s));
                } catch (IllegalArgumentException ignored) {
                    // a role this platform does not know grants nothing
                }
            }
        }
        try {
            return new Actor(jwt.getSubject(), roles, jwt.getClaimAsString("facility"));
        } catch (IllegalArgumentException e) { // valid signature but claims that do not describe an actor
            throw new com.medigraph.common.error.AccessDeniedException("token does not describe a valid actor: " + e.getMessage());
        }
    }
}
