package com.sam.apnasamaan.customer.service;

import com.sam.apnasamaan.auth.entity.AuthProvider;
import com.sam.apnasamaan.auth.entity.Role;
import com.sam.apnasamaan.auth.entity.User;
import com.sam.apnasamaan.auth.repository.UserRepository;
import com.sam.apnasamaan.auth.security.UserPrincipal;
import com.sam.apnasamaan.common.exception.ForbiddenOperationException;
import com.sam.apnasamaan.common.exception.ResourceNotFoundException;
import com.sam.apnasamaan.customer.dto.CustomerProfileResponse;
import com.sam.apnasamaan.customer.dto.CustomerProfileUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class CustomerService {

    private final UserRepository userRepository;

    public CustomerService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public CustomerProfileResponse getCustomerProfile(Long targetUserId, UserPrincipal requester) {
        enforceOwnershipOrAdmin(targetUserId, requester);
        User customer = loadCustomerUser(targetUserId);
        return toResponse(customer);
    }

    @Transactional
    public CustomerProfileResponse updateCustomerProfile(
            Long targetUserId,
            UserPrincipal requester,
            CustomerProfileUpdateRequest request
    ) {
        // Only the customer themselves can mutate their own profile
        if (!targetUserId.equals(requester.getId()) || requester.getRole() != Role.CUSTOMER) {
            throw new ForbiddenOperationException("You can only update your own customer profile");
        }

        User customer = loadCustomerUser(targetUserId);
        customer.setName(request.name().trim());
        customer.setPhone(request.phone().trim());

        User saved = userRepository.save(customer);
        log.info("Updated profile for customer userId={}", saved.getId());
        return toResponse(saved);
    }

    private void enforceOwnershipOrAdmin(Long targetUserId, UserPrincipal requester) {
        boolean isOwner = targetUserId.equals(requester.getId());
        boolean isAdmin = requester.getRole() == Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new ForbiddenOperationException("You are not allowed to access another customer's profile");
        }
    }

    private User loadCustomerUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer profile not found"));
        if (user.getRole() != Role.CUSTOMER) {
            throw new ResourceNotFoundException("Customer profile not found");
        }
        return user;
    }

    private CustomerProfileResponse toResponse(User user) {
        boolean googleLinked = user.getGoogleSub() != null && !user.getGoogleSub().isBlank();
        return new CustomerProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getAuthProvider() != null ? user.getAuthProvider() : AuthProvider.LOCAL,
                googleLinked,
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
