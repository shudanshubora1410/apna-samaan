package com.sam.apnasamaan.auth.mapper;

import com.sam.apnasamaan.auth.dto.UserSummaryResponse;
import com.sam.apnasamaan.auth.entity.AuthProvider;
import com.sam.apnasamaan.auth.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserSummaryResponse toSummary(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getAuthProvider() != null ? user.getAuthProvider() : AuthProvider.LOCAL,
                user.isEnabled(),
                user.getCreatedAt()
        );
    }
}
