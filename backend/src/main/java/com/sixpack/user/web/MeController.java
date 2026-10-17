package com.sixpack.user.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.shared.security.AuthUser;
import com.sixpack.shared.web.ApiResponse;
import com.sixpack.user.application.ProfileService;
import com.sixpack.user.application.ProfileService.AddressCommand;
import com.sixpack.user.application.ProfileService.ProfileCommand;
import com.sixpack.user.domain.Address;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own profile and address book. */
@Validated
@RestController
@RequestMapping("/api/v1/user/me")
class MeController {

    static final String PHONE = "^\\+?[0-9]{10,13}$";

    record ProfileRequest(
            @Size(max = 80) String name,
            @Pattern(regexp = "^\\+?[0-9]{10,13}$|", message = "must be 10-13 digits, optionally starting with +")
            String phone,
            @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$|", message = "must look like en or en-IN") String locale) {
    }

    record AddressRequest(
            @Size(max = 30) String label,
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Pattern(regexp = PHONE, message = "must be 10-13 digits, optionally starting with +")
            String phone,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Size(max = 80) String state,
            @NotBlank @Size(max = 60) String country,
            @NotBlank @Pattern(regexp = "\\d{4,10}", message = "must be 4-10 digits") String pinCode,
            @JsonProperty("isDefault") boolean isDefault) {
        AddressCommand toCommand() {
            return new AddressCommand(label, name, phone, line1, line2, city, state, country, pinCode, isDefault);
        }
    }

    record AddressView(String id, String label, String name, String phone, String line1, String line2, String city,
                       String state, String country, String pinCode, @JsonProperty("isDefault") boolean isDefault) {
        static AddressView from(Address a) {
            return new AddressView(a.id(), a.label(), a.name(), a.phone(), a.line1(), a.line2(), a.city(),
                    a.state(), a.country(), a.pinCode(), a.isDefault());
        }
    }

    private final ProfileService profile;

    MeController(ProfileService profile) {
        this.profile = profile;
    }

    @PutMapping
    Map<String, Object> update(@Valid @RequestBody ProfileRequest request, @AuthenticationPrincipal Jwt jwt) {
        var user = profile.updateProfile(AuthUser.from(jwt).id(),
                new ProfileCommand(request.name(), request.phone(), request.locale()));
        return ApiResponse.of("message", "Profile updated successfully", "user", UserView.from(user));
    }

    @GetMapping("/addresses")
    Map<String, Object> addresses(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of("addresses",
                profile.addresses(AuthUser.from(jwt).id()).stream().map(AddressView::from).toList());
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> add(@Valid @RequestBody AddressRequest request, @AuthenticationPrincipal Jwt jwt) {
        var created = profile.addAddress(AuthUser.from(jwt).id(), request.toCommand());
        return ApiResponse.of("message", "Address saved", "address", AddressView.from(created));
    }

    @PutMapping("/addresses/{addressId}")
    Map<String, Object> update(@PathVariable String addressId, @Valid @RequestBody AddressRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        var updated = profile.updateAddress(AuthUser.from(jwt).id(), addressId, request.toCommand());
        return ApiResponse.of("message", "Address updated", "address", AddressView.from(updated));
    }

    @DeleteMapping("/addresses/{addressId}")
    Map<String, Object> delete(@PathVariable String addressId, @AuthenticationPrincipal Jwt jwt) {
        profile.deleteAddress(AuthUser.from(jwt).id(), addressId);
        return ApiResponse.message("Address deleted");
    }
}
