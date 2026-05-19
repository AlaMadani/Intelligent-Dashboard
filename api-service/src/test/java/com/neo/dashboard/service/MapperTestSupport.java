package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.mapper.JsonParsingSupport;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

final class MapperTestSupport {

    private static final JsonParsingSupport JSON_PARSING_SUPPORT =
            new JsonParsingSupport(new ObjectMapper().findAndRegisterModules());

    private MapperTestSupport() {
    }

    static <T> T mapperWithJsonSupport(Class<T> mapperType) {
        T mapper = Mappers.getMapper(mapperType);
        ReflectionTestUtils.setField(mapper, "jsonParsingSupport", JSON_PARSING_SUPPORT);
        return mapper;
    }
}
