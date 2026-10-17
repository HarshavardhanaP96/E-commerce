package com.sixpack.consent.web;

import com.sixpack.consent.ConsentEvidence;
import com.sixpack.consent.Purpose;
import com.sixpack.consent.application.ConsentService;
import com.sixpack.consent.application.ConsentService.ConsentState;
import com.sixpack.consent.domain.ConsentRecord;
import com.sixpack.consent.domain.ConsentRecord.Source;
import com.sixpack.shared.security.AuthUser;
import com.sixpack.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ConsentController {

    record DecisionRequest(
            @NotBlank @Pattern(regexp = "(?i)terms|privacy|marketing_email",
                    message = "must be terms, privacy or marketing_email") String purpose,
            @NotNull Boolean granted,
            @NotBlank @Pattern(regexp = "(?i)reprompt|settings", message = "must be reprompt or settings")
            String source) {
    }

    record ConsentView(boolean granted, String version, Instant at) {
        static ConsentView from(ConsentRecord r) {
            return new ConsentView(r.granted(), r.policyVersion(), r.createdAt());
        }
    }

    record HistoryView(String purpose, boolean granted, String version, String source, Instant at) {
        static HistoryView from(ConsentRecord r) {
            return new HistoryView(r.purpose().wireValue(), r.granted(), r.policyVersion(), r.source().wireValue(),
                    r.createdAt());
        }
    }

    private final ConsentService consents;

    ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    @GetMapping("/api/v1/legal/versions")
    Map<String, Object> versions() {
        Map<String, String> versions = new LinkedHashMap<>();
        consents.currentVersions().forEach((p, v) -> versions.put(p.wireValue(), v));
        return ApiResponse.of("versions", versions);
    }

    @GetMapping("/api/v1/consent/me")
    Map<String, Object> mine(@AuthenticationPrincipal Jwt jwt) {
        return view(consents.state(AuthUser.from(jwt).id()));
    }

    @PostMapping("/api/v1/consent")
    Map<String, Object> decide(@Valid @RequestBody DecisionRequest request, @AuthenticationPrincipal Jwt jwt,
                               HttpServletRequest http) {
        return view(consents.decide(AuthUser.from(jwt).id(), Purpose.fromWire(request.purpose()),
                request.granted(), Source.fromWire(request.source()), evidence(http)));
    }

    static ConsentEvidence evidence(HttpServletRequest http) {
        return new ConsentEvidence(http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
    }

    private static Map<String, Object> view(ConsentState state) {
        Map<String, ConsentView> latest = new LinkedHashMap<>();
        state.latest().forEach((p, r) -> latest.put(p.wireValue(), ConsentView.from(r)));
        List<String> pending = state.pending().stream().map(Purpose::wireValue).toList();
        return ApiResponse.of("consents", latest, "pending", pending,
                "history", state.history().stream().map(HistoryView::from).toList());
    }
}
