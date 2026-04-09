package com.noveocare.dataprocessor.mapper;

import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Shared mapper contract used by all DTO/entity conversions.
 */
public interface GenericMapper<D, E> {

    D toDto(E entity);

    E toEntity(D dto);

    void updateEntity(D dto, @MappingTarget E entity);

    default List<D> toDtoList(List<E> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream()
                .map(this::toDto)
                .toList();
    }

    default List<E> toEntityList(List<D> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        return dtos.stream()
                .map(this::toEntity)
                .toList();
    }
}
