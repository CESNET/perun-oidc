package cz.muni.ics.oidc.server.filters;

import static cz.muni.ics.oidc.server.filters.PerunFilterConstants.AUTHORIZE_REQ_PATTERN;
import static cz.muni.ics.oidc.server.filters.PerunFilterConstants.DEVICE_CHECK_CODE_REQ_PATTERN;

import cz.muni.ics.oauth2.model.ClientDetailsEntity;
import cz.muni.ics.oauth2.service.ClientDetailsEntityService;
import cz.muni.ics.oidc.BeanUtil;
import cz.muni.ics.oidc.models.Facility;
import cz.muni.ics.oidc.models.PerunUser;
import cz.muni.ics.oidc.saml.SamlProperties;
import cz.muni.ics.oidc.server.adapters.PerunAdapter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import javax.annotation.PostConstruct;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.provider.OAuth2RequestFactory;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.GenericFilterBean;

/**
 * This filter calls other Perun filters saved in the PerunFiltersContext
 *
 * @author Dominik Baranek <baranek@ics.muni.cz>
 * @author Dominik Frantisek Bucik <bucik@ics.muni.cz>
 */
@Slf4j
public class CallPerunFiltersFilter extends GenericFilterBean {

    private static final RequestMatcher AUTHORIZE_MATCHER = new AntPathRequestMatcher(AUTHORIZE_REQ_PATTERN);
    private static final RequestMatcher AUTHORIZE_ALL_MATCHER = new AntPathRequestMatcher(AUTHORIZE_REQ_PATTERN + "/**");
    private static final RequestMatcher DEVICE_CODE_MATCHER = new AntPathRequestMatcher(DEVICE_CHECK_CODE_REQ_PATTERN);
    private static final RequestMatcher DEVICE_CODE_ALL_MATCHER = new AntPathRequestMatcher(DEVICE_CHECK_CODE_REQ_PATTERN + "/**");
    private static final RequestMatcher MATCHER = new OrRequestMatcher(
            Arrays.asList(AUTHORIZE_MATCHER, AUTHORIZE_ALL_MATCHER, DEVICE_CODE_MATCHER, DEVICE_CODE_ALL_MATCHER));

    @Autowired
    private Properties coreProperties;

    @Autowired
    private BeanUtil beanUtil;

    @Autowired
    private OAuth2RequestFactory authRequestFactory;

    @Autowired
    private ClientDetailsEntityService clientDetailsEntityService;

    @Autowired
    private PerunAdapter perunAdapter;

    @Autowired
    private SamlProperties samlProperties;

    private PerunFiltersContext perunFiltersContext;

    @PostConstruct
    public void postConstruct() {
        this.perunFiltersContext = new PerunFiltersContext(coreProperties, beanUtil);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException
    {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        if (!MATCHER.matches(request)) {
            log.debug("Custom filters have been skipped, did not match '/authorize' nor '/device/code' request");
        } else {
            List<PerunRequestFilter> filters = perunFiltersContext.getFilters();
            if (filters != null && !filters.isEmpty()) {
                ClientDetailsEntity client = FiltersUtils.extractClientFromRequest(request, authRequestFactory,
                        clientDetailsEntityService);
                Facility facility = null;
                if (client != null && StringUtils.hasText(client.getClientId())) {
                    try {
                        facility = perunAdapter.getFacilityByClientId(client.getClientId());
                    } catch (Exception e) {
                        log.warn("{} - could not fetch facility for client_id '{}'",
                                CallPerunFiltersFilter.class.getSimpleName(), client.getClientId(), e);
                    }
                }
                PerunUser user = FiltersUtils.getPerunUser(request, perunAdapter,
                        samlProperties.getUserIdentifierAttribute());
                FilterParams params = new FilterParams(client, facility, user);
                for (PerunRequestFilter filter : filters) {
                    if (!filter.doFilter(servletRequest, servletResponse, params)) {
                        return;
                    }
                }
            }
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }

}
