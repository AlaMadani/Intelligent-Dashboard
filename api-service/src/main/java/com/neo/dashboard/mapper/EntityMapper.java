package com.neo.dashboard.mapper;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Generic contract reused by entity-to-DTO mappers.
 */
public interface EntityMapper<D, E> {

    D toDto(E entity);

    default List<D> toDtoList(Collection<E> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        return entities.stream().map(this::toDto).toList();
    }
}

