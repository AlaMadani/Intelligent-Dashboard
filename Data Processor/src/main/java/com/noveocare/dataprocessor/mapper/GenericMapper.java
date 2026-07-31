package com.noveocare.dataprocessor.mapper;

import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Shared mapper contract used by all DTO/entity conversions.
 */
public interface GenericMapper<D, E> {

    /* Convert an entity to its corresponding DTO. */
    D toDto(E entity);

    /* Convert a DTO to its corresponding entity. */
    E toEntity(D dto);

    /* Merge DTO fields into an existing entity instance. */
    void updateEntity(D dto, @MappingTarget E entity);

    /* Batch conversion: list of entities to list of DTOs. */
    default List<D> toDtoList(List<E> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream()
                .map(this::toDto)
                .toList();
    }

    /* Batch conversion: list of DTOs to list of entities. */
    default List<E> toEntityList(List<D> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        return dtos.stream()
                .map(this::toEntity)
                .toList();
    }
}
