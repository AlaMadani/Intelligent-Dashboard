package com.neo.dashboard.mapper;

import com.neo.dashboard.dto.AuthResponse;
import com.neo.dashboard.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface AuthApiMapper {

    /**
     * Maps a {@link User} entity to a {@link AuthResponse.UserAuthDto}.
     * The {@code role} field is converted from the enum to its string
     * representation via a custom expression.
     */
    @Mapping(target = "role", expression = "java(user.getRole().toString())")
    AuthResponse.UserAuthDto toUserAuthDto(User user);
}
