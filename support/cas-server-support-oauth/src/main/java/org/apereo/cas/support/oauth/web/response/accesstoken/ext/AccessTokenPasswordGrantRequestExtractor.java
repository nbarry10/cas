package org.apereo.cas.support.oauth.web.response.accesstoken.ext;

import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.audit.AuditableContext;
import org.apereo.cas.audit.AuditableExecution;
import org.apereo.cas.audit.AuditableExecutionResult;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.DefaultAuthenticationResult;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.configuration.model.support.oauth.OAuthProperties;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.services.UnauthorizedServiceException;
import org.apereo.cas.support.oauth.OAuth20Constants;
import org.apereo.cas.support.oauth.OAuth20GrantTypes;
import org.apereo.cas.support.oauth.authenticator.OAuth20CasAuthenticationBuilder;
import org.apereo.cas.support.oauth.services.OAuthRegisteredService;
import org.apereo.cas.support.oauth.util.OAuth20Utils;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.apereo.cas.util.Pac4jUtils;
import org.pac4j.core.context.J2EContext;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.core.profile.UserProfile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.Set;

/**
 * This is {@link AccessTokenPasswordGrantRequestExtractor}.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
@Slf4j
public class AccessTokenPasswordGrantRequestExtractor extends BaseAccessTokenGrantRequestExtractor {

    private final AuditableExecution registeredServiceAccessStrategyEnforcer;
    private final OAuth20CasAuthenticationBuilder authenticationBuilder;

    public AccessTokenPasswordGrantRequestExtractor(final ServicesManager servicesManager,
                                                    final TicketRegistry ticketRegistry,
                                                    final OAuth20CasAuthenticationBuilder authenticationBuilder,
                                                    final CentralAuthenticationService centralAuthenticationService,
                                                    final OAuthProperties oAuthProperties,
                                                    final AuditableExecution registeredServiceAccessStrategyEnforcer) {
        super(servicesManager, ticketRegistry, centralAuthenticationService, oAuthProperties);
        this.authenticationBuilder = authenticationBuilder;
        this.registeredServiceAccessStrategyEnforcer = registeredServiceAccessStrategyEnforcer;
    }

    @Override
    public AccessTokenRequestDataHolder extract(final HttpServletRequest request, final HttpServletResponse response) {
        final String clientId = request.getParameter(OAuth20Constants.CLIENT_ID);
        final Set<String> scopes = OAuth20Utils.parseRequestScopes(request);
        LOGGER.debug("Locating OAuth registered service by client id [{}]", clientId);

        final OAuthRegisteredService registeredService = OAuth20Utils.getRegisteredOAuthServiceByClientId(this.servicesManager, clientId);
        LOGGER.debug("Located OAuth registered service [{}]", registeredService);

        final J2EContext context = Pac4jUtils.getPac4jJ2EContext(request, response);
        final ProfileManager manager = Pac4jUtils.getPac4jProfileManager(request, response);
        final Optional<UserProfile> profile = manager.get(true);
        if (!profile.isPresent()) {
            throw new UnauthorizedServiceException("OAuth user profile cannot be determined");
        }
        final UserProfile uProfile = profile.get();
        LOGGER.debug("Creating matching service request based on [{}]", registeredService);
        final boolean requireServiceHeader = oAuthProperties.getGrants().getResourceOwner().isRequireServiceHeader();
        if (requireServiceHeader) {
            LOGGER.debug("Using request headers to identify and build the target service url");
        }
        final Service service = this.authenticationBuilder.buildService(registeredService, context, requireServiceHeader);

        LOGGER.debug("Authenticating the OAuth request indicated by [{}]", service);
        final Authentication authentication = this.authenticationBuilder.build(uProfile, registeredService, context, service);


        final AuditableContext audit = AuditableContext.builder().service(Optional.of(service))
            .authentication(Optional.of(authentication))
            .registeredService(Optional.of(registeredService))
            .retrievePrincipalAttributesFromReleasePolicy(Optional.of(Boolean.TRUE))
            .build();
        final AuditableExecutionResult accessResult = this.registeredServiceAccessStrategyEnforcer.execute(audit);
        accessResult.throwExceptionIfNeeded();

        final AuthenticationResult result = new DefaultAuthenticationResult(authentication, requireServiceHeader ? service : null);
        final TicketGrantingTicket ticketGrantingTicket = this.centralAuthenticationService.createTicketGrantingTicket(result);

        return new AccessTokenRequestDataHolder(service, authentication, registeredService, ticketGrantingTicket, getGrantType(), scopes);
    }

    @Override
    public boolean supports(final HttpServletRequest context) {
        final String grantType = context.getParameter(OAuth20Constants.GRANT_TYPE);
        return OAuth20Utils.isGrantType(grantType, getGrantType());
    }

    @Override
    public OAuth20GrantTypes getGrantType() {
        return OAuth20GrantTypes.PASSWORD;
    }
}
