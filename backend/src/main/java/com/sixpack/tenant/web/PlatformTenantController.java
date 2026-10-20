package com.sixpack.tenant.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.shared.tenant.LegalSettings;
import com.sixpack.shared.tenant.MailSettings;
import com.sixpack.shared.tenant.PaymentSettings;
import com.sixpack.shared.tenant.StorefrontSettings;
import com.sixpack.shared.web.ApiResponse;
import com.sixpack.tenant.application.TenantAdminService;
import com.sixpack.tenant.application.TenantAdminService.AdminCommand;
import com.sixpack.tenant.application.TenantAdminService.CreateCommand;
import com.sixpack.tenant.application.TenantAdminService.UpdateCommand;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.application.TenantExport;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tenant management for platform administrators (role PLATFORM_ADMIN, enforced in the security configuration). */
@RestController
@RequestMapping("/api/v1/platform/tenants")
class PlatformTenantController {

    record AdminRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 120) String email,
                        @NotBlank @Size(max = 72) String password) {
    }

    record CreateRequest(@NotBlank @Size(max = 40) String slug, @NotBlank @Size(max = 80) String name,
                         @Size(max = 20) List<String> domains, Map<String, String> theme,
                         @Size(max = 16) String locale, @Size(max = 8) String currency, LegalSettings legal,
                         PaymentSettings payment, MailSettings mail, StorefrontSettings storefront,
                         Map<String, Boolean> features, @Valid AdminRequest admin) {
    }

    record UpdateRequest(@Size(max = 80) String name, @Size(max = 20) List<String> domains,
                         Map<String, String> theme, @Size(max = 16) String locale, @Size(max = 8) String currency,
                         LegalSettings legal, PaymentSettings payment, MailSettings mail,
                         StorefrontSettings storefront, Map<String, Boolean> features) {
    }

    record TenantView(@JsonProperty("id") String id, String slug, String name, String status, List<String> domains,
                      Map<String, String> theme, String locale, String currency, LegalSettings legal,
                      PaymentSettings payment, MailSettings mail, StorefrontSettings storefront,
                      Map<String, Boolean> features, Instant deletionDueAt, Instant createdAt) {
        static TenantView from(Tenant t) {
            return new TenantView(t.id(), t.slug(), t.name(), t.status().name(), t.domains(), t.theme(), t.locale(),
                    t.currency(), t.legal(), t.payment(), t.mail(), t.storefront(), t.features(), t.deletionDueAt(),
                    t.createdAt());
        }
    }

    private final TenantAdminService service;
    private final TenantExport export;

    PlatformTenantController(TenantAdminService service, TenantExport export) {
        this.service = service;
        this.export = export;
    }

    @GetMapping
    Map<String, Object> list() {
        return ApiResponse.of("tenants", service.list().stream().map(TenantView::from).toList());
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id) {
        return ApiResponse.of("tenant", TenantView.from(service.get(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@Valid @RequestBody CreateRequest r) {
        AdminCommand admin = r.admin() == null ? null
                : new AdminCommand(r.admin().name(), r.admin().email(), r.admin().password());
        Tenant created = service.create(new CreateCommand(r.slug(), r.name(), r.domains(), r.theme(), r.locale(),
                r.currency(), r.legal(), r.payment(), r.mail(), r.storefront(), r.features(), admin));
        return ApiResponse.of("message", "Tenant " + created.slug() + " created", "tenant", TenantView.from(created));
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateRequest r) {
        Tenant updated = service.update(id, new UpdateCommand(r.name(), r.domains(), r.theme(), r.locale(),
                r.currency(), r.legal(), r.payment(), r.mail(), r.storefront(), r.features()));
        return ApiResponse.of("message", "Tenant updated", "tenant", TenantView.from(updated));
    }

    @PostMapping("/{id}/suspend")
    Map<String, Object> suspend(@PathVariable String id) {
        return ApiResponse.of("message", "Tenant suspended", "tenant", TenantView.from(service.suspend(id)));
    }

    /** Downloads the store's data and photos as a ZIP. */
    @GetMapping("/{id}/export")
    void export(@PathVariable String id, HttpServletResponse response) throws IOException {
        String slug = service.get(id).slug();
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + slug + "-export.zip\"");
        export.exportTo(id, response.getOutputStream());
    }

    @PostMapping("/{id}/offboard")
    Map<String, Object> offboard(@PathVariable String id) {
        return ApiResponse.of("message", "Store closed and scheduled for deletion", "tenant",
                TenantView.from(service.offboard(id)));
    }

    @PostMapping("/{id}/restore")
    Map<String, Object> restore(@PathVariable String id) {
        return ApiResponse.of("message", "Deletion cancelled; the store is active", "tenant",
                TenantView.from(service.restore(id)));
    }

    @PostMapping("/{id}/activate")
    Map<String, Object> activate(@PathVariable String id) {
        return ApiResponse.of("message", "Tenant activated", "tenant", TenantView.from(service.activate(id)));
    }
}
