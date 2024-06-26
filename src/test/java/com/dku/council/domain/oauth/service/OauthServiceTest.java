package com.dku.council.domain.oauth.service;

import com.dku.council.domain.oauth.exception.InvalidGrantTypeException;
import com.dku.council.domain.oauth.exception.InvalidOauthResponseTypeException;
import com.dku.council.domain.oauth.exception.OauthClientNotFoundException;
import com.dku.council.domain.oauth.model.dto.request.*;
import com.dku.council.domain.oauth.model.dto.response.RedirectResponse;
import com.dku.council.domain.oauth.model.dto.response.TokenExchangeResponse;
import com.dku.council.domain.oauth.model.entity.OauthClient;
import com.dku.council.domain.oauth.repository.OauthClientRepository;
import com.dku.council.domain.oauth.repository.OauthConnectionRepository;
import com.dku.council.domain.oauth.repository.OauthRedisRepository;
import com.dku.council.domain.oauth.util.CodeChallengeConverter;
import com.dku.council.domain.user.exception.WrongPasswordException;
import com.dku.council.domain.user.model.dto.request.RequestLoginDto;
import com.dku.council.domain.user.model.entity.Major;
import com.dku.council.domain.user.model.entity.User;
import com.dku.council.domain.user.repository.UserRepository;
import com.dku.council.global.auth.jwt.JwtAuthenticationToken;
import com.dku.council.global.auth.jwt.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.UriComponentsBuilder;


import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OauthServiceTest {
    @InjectMocks
    private OauthService oauthService;

    @Mock
    private OauthClientRepository oauthClientRepository;

    @Mock
    private OauthRedisRepository oauthRedisRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OauthConnectionRepository oauthConnectionRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private CodeChallengeConverter codeChallengeConverter;

    private OauthRequest oauthRequest;
    private OauthClient oauthClient;
    private RequestLoginDto loginInfo;
    private OauthInfo oauthInfo;
    private ClientInfo clientInfo;
    private OAuthTarget target;
    private String codeVerifier;
    private String codeChallenge;
    private String codeChallengeMethod;
    private String clientId;
    private String redirectUri;
    private String responseType;
    private String scope;
    private String code;
    private User user;
    private OauthCachePayload cachePayload;

    @BeforeEach
    void setUp() {
        codeVerifier = "jHrCMUS-yiO644eT4M4yJdW-o4wOC3Yg89piwUUMdQdsg83P";
        codeChallenge = "gNZrRlO16h6I0tkoFmjwdrFEqsQAgJQtvv7EiA8sgPs";
        codeChallengeMethod = "S256";
        clientId = "clientId";
        redirectUri = "https://server.com/oauth/redirect";
        responseType = "code";
        scope = "name email";
        String grantType = "authorization_code";
        String clientSecret = "clientSecret";
        code = "code";
        oauthRequest = OauthRequest.of(codeChallenge, clientId, redirectUri, responseType, scope);
        oauthClient = OauthClient.of(clientId, "app", clientSecret, redirectUri, scope);
        loginInfo = new RequestLoginDto("32173437", "1234");
        oauthInfo = OauthInfo.of(clientId, redirectUri, codeChallenge, codeChallengeMethod, scope, responseType);
        clientInfo = ClientInfo.of(clientId, clientSecret, redirectUri);
        target = OAuthTarget.of(grantType, code, codeVerifier);
        Major major = new Major("소프트웨어학과", "소프트웨어융합대학");
        user = User.builder().studentId("32240000").password("1234").name("이름").major(major)
                .phone("010-1234-5678").nickname("닉네임").academicStatus("재학").age("20").gender("남").build();
        cachePayload = OauthCachePayload.of(1L, codeChallenge, codeChallengeMethod, scope);
        oauthClientRepository.save(oauthClient);
        userRepository.save(user);
        oauthRedisRepository.cacheOauth("code", cachePayload);
    }

    @Test
    void authorizeWhenValidRequest() {
        // given
        final String LOGIN_URL = "https://oauth.danvery.com/signin";
        ReflectionTestUtils.setField(oauthService, "LOGIN_URL", "https://oauth.danvery.com/signin");
        when(oauthClientRepository.findByClientId(anyString())).thenReturn(Optional.of(oauthClient));

        // when
        RedirectResponse result = oauthService.authorize(oauthRequest);

        // then
        assertEquals(UriComponentsBuilder.
                fromUriString(LOGIN_URL).
                queryParams(oauthRequest.toQueryParams()).
                toUriString(), result.getRedirectUri());

    }

    @Test
    void throwExceptionWhenInvalidResponseType() {
        // given
        OauthRequest invalidRequest = OauthRequest.of(codeChallenge, codeChallengeMethod, clientId,
                redirectUri, "invalid", scope);

        // when, then
        assertThrows(InvalidOauthResponseTypeException.class, () -> oauthService.authorize(invalidRequest),
                "invalid.oauth-response-type: invalid");
    }

    @Test
    void returnTermsUrlWhenLoginTheFirst() {
        // given
        final String TERMS_URL = "https://oauth.danvery.com/terms";
        ReflectionTestUtils.setField(oauthService, "TERMS_URL", TERMS_URL);
        when(userRepository.findByStudentId(any())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(oauthClientRepository.findByClientId(any())).thenReturn(Optional.of(oauthClient));
        when(oauthConnectionRepository.findByUserAndOauthClient(any(), any())).thenReturn(Optional.empty());

        // when
        RedirectResponse result = oauthService.login(loginInfo, oauthInfo);

        // then
        assertEquals(UriComponentsBuilder.
                fromUriString(TERMS_URL).
                queryParams(oauthInfo.toQueryParams(oauthClient.getScope(), user.getStudentId(), oauthClient.getApplicationName())).
                toUriString(), result.getRedirectUri());
    }

    @Test
    void throwExceptionWhenInvalidPassword() {
        // given
        when(userRepository.findByStudentId(any())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        // when, then
        assertThrows(WrongPasswordException.class, () -> oauthService.login(loginInfo, oauthInfo),
                "invalid.password");
    }

    @Test
    void exchangeTokenWhenValidClientInfo() throws NoSuchAlgorithmException {
        // given
        JwtAuthenticationToken authenticationToken = JwtAuthenticationToken.builder().accessToken("ATK")
                .refreshToken("RTK").build();
        when(oauthClientRepository.findByClientId(any())).thenReturn(Optional.of(oauthClient));
        when(userRepository.findById(any())).thenReturn(Optional.of(user));
        when(oauthRedisRepository.getOauth(any())).thenReturn(Optional.of(cachePayload));
        when(codeChallengeConverter.convertToCodeChallenge(any(), any())).thenReturn(codeChallenge);
        when(jwtProvider.issue(any())).thenReturn(authenticationToken);
        // when
        TokenExchangeResponse response = oauthService.exchangeToken(clientInfo, target);

        // then
        assertNotNull(response);
    }

    @Test
    void throwExceptionWhenInvalidGrantType() {
        // given
        OAuthTarget target = OAuthTarget.of("invalid", code, codeVerifier);

        // when, then
        assertThrows(InvalidGrantTypeException.class, () -> oauthService.exchangeToken(clientInfo, target)
                , "invalid.oauth-grant-type: invalid");
    }


    @Test
    void checkClientIdNotExist() {
        // given
        String clientId = "notExistClientId";
        OauthRequest request = OauthRequest.of(codeChallenge, codeChallengeMethod, clientId,
                redirectUri, responseType, scope);

        // when, then
        assertThrows(OauthClientNotFoundException.class, () -> oauthService.authorize(request)
                , "notfound.oauth-client: notExistClientId");
    }
}