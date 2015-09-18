/*******************************************************************************
 * logsniffer, open source tool for viewing, monitoring and analysing log data.
 * Copyright (c) 2015 Scaleborn UG, www.scaleborn.com
 *
 * logsniffer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * logsniffer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package com.logsniffer.model.fields;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.UntypedObjectDeserializer;
import com.fasterxml.jackson.databind.node.TextNode;
import com.logsniffer.app.ContextProvider;
import com.logsniffer.model.fields.FieldsMap.FieldsMapTypeSafeDeserializer;
import com.logsniffer.model.fields.FieldsMap.FieldsMapTypeSafeSerializer;

/**
 * Fields map of a log entry.
 * 
 * @author mbok
 * 
 */
@JsonSerialize(using = FieldsMapTypeSafeSerializer.class)
@JsonDeserialize(using = FieldsMapTypeSafeDeserializer.class)
public final class FieldsMap extends LinkedHashMap<String, Object> implements
		FieldsMapPublicSerializationMixIn {
	private static final long serialVersionUID = -3923428304353465704L;

	private LinkedHashMap<String, FieldBaseTypes> types;

	public FieldsMap() {
		super();
	}

	public FieldsMap(final LinkedHashMap<String, FieldBaseTypes> types) {
		super();
		this.types = types;
	}

	@Override
	public Object put(final String key, final Object value) {
		if (types != null && !types.containsKey(key)) {
			types.put(key, FieldBaseTypes.resolveType(value));
		}
		return super.put(key, value);
	}

	/**
	 * @return the types
	 */
	@Override
	public Map<String, FieldBaseTypes> getTypes() {
		if (types == null) {
			types = new LinkedHashMap<String, FieldBaseTypes>();
			for (Map.Entry<String, Object> e : entrySet()) {
				types.put(e.getKey(), FieldBaseTypes.resolveType(e.getValue()));
			}
		}
		return types;
	}

	/**
	 * @param types
	 *            the types to set
	 */
	public void setTypes(final LinkedHashMap<String, FieldBaseTypes> types) {
		this.types = types;
	}

	@Override
	public Map<String, Object> getAll() {
		return this;
	}

	/**
	 * Serializes a {@link FieldsMap} following the
	 * {@link FieldsMapPublicSerializationMixIn} annotation instead of the map
	 * nature to include {@link FieldsMap#getTypes()} property.
	 * 
	 * @author mbok
	 * 
	 */
	public static class FieldsMapMixInLikeSerializer extends
			JsonSerializer<FieldsMap> {

		@Override
		public void serialize(final FieldsMap value, final JsonGenerator jgen,
				final SerializerProvider provider) throws IOException,
				JsonProcessingException {
			provider.findValueSerializer(
					FieldsMapPublicSerializationMixIn.class, null).serialize(
					value, jgen, provider);
		}

	}

	/**
	 * Type safe deserializer for {@link FieldsMap}.
	 * 
	 * @author mbok
	 * 
	 */
	public static class FieldsMapTypeSafeSerializer extends
			JsonSerializer<FieldsMap> {
		private FieldJsonMapper fieldTypeMapper;

		private FieldJsonMapper getTypeMapper() {
			if (fieldTypeMapper == null) {
				fieldTypeMapper = ContextProvider.getContext().getBean(
						FieldJsonMapper.class);
			}
			return fieldTypeMapper;
		}

		@Override
		public void serialize(final FieldsMap value, final JsonGenerator jgen,
				final SerializerProvider provider) throws IOException,
				JsonProcessingException {
			jgen.writeStartObject();
			if (!value.isEmpty()) {
				jgen.writeFieldName("@types");
				jgen.writeStartObject();
				for (String key : value.keySet()) {
					Object v = value.get(key);
					if (v != null) {
						jgen.writeStringField(key, getTypeMapper()
								.resolveSerializationType(v));
					}
				}
				jgen.writeEndObject();
				for (String key : value.keySet()) {
					jgen.writeFieldName(key);
					provider.defaultSerializeValue(value.get(key), jgen);
				}
			}
			jgen.writeEndObject();
		}

	}

	/**
	 * Type safe deserializer for {@link FieldsMap}.
	 * 
	 * @author mbok
	 * 
	 */
	public static class FieldsMapTypeSafeDeserializer extends
			JsonDeserializer<FieldsMap> {
		private static final Logger LOGGER = LoggerFactory
				.getLogger(FieldsMapTypeSafeDeserializer.class);
		private final UntypedObjectDeserializer primitiveDeserializer = new UntypedObjectDeserializer();
		private final Map<Class<?>, JsonDeserializer<?>> cachedDeserializer = new HashMap<Class<?>, JsonDeserializer<?>>();
		private FieldJsonMapper fieldTypeMapper;

		private FieldJsonMapper getTypeMapper() {
			if (fieldTypeMapper == null) {
				fieldTypeMapper = ContextProvider.getContext().getBean(
						FieldJsonMapper.class);
			}
			return fieldTypeMapper;
		}

		@Override
		public FieldsMap deserialize(final JsonParser jp,
				final DeserializationContext ctxt) throws IOException,
				JsonProcessingException {
			FieldsMap fields = new FieldsMap();
			// @types
			Map<String, Class<?>> types = new HashMap<String, Class<?>>();
			TreeNode readTree = jp.getCodec().readTree(jp);
			TreeNode typesNodes = readTree.get("@types");
			if (typesNodes != null) {
				Iterator<String> typeFields = typesNodes.fieldNames();
				while (typeFields.hasNext()) {
					String key = typeFields.next();
					TreeNode typeNode = typesNodes.get(key);
					if (typeNode instanceof TextNode) {
						String type = ((TextNode) typeNode).textValue();
						Class<?> deserType = getTypeMapper()
								.resolveDeserializationType(type);
						types.put(key, deserType);
					}
				}
			} else {
				LOGGER.warn(
						"Missing field type information, type-safe deserialization will not be supported for: {}",
						readTree);
			}

			// Deserialize values
			Iterator<String> fieldNames = readTree.fieldNames();
			while (fieldNames.hasNext()) {
				String key = fieldNames.next();
				if (!key.equals("@types")) {
					Class<?> targetType = types.get(key);
					TreeNode fieldNode = readTree.get(key);
					JsonParser fieldValueParser = fieldNode.traverse();
					// Start
					fieldValueParser.nextToken();
					if (targetType != null) {
						Object o = getContextualValueDeserializer(targetType,
								ctxt).deserialize(fieldValueParser, ctxt);
						fields.put(key, o);
					} else {
						// Unknown type, let Jackson do it
						fields.put(key, primitiveDeserializer.deserialize(
								fieldValueParser, ctxt));
					}
				}
			}
			return fields;
		}

		@SuppressWarnings("unchecked")
		private <T> JsonDeserializer<T> getContextualValueDeserializer(
				final Class<T> forClazz, final DeserializationContext ctxt)
				throws JsonMappingException {
			if (!cachedDeserializer.containsKey(forClazz)) {
				cachedDeserializer.put(
						forClazz,
						ctxt.findContextualValueDeserializer(
								ctxt.constructType(forClazz), null));
			}
			return (JsonDeserializer<T>) cachedDeserializer.get(forClazz);
		}
	}
}

/**
 * Mix-in for public serialization of {@link FieldsMap}.
 * 
 * @author mbok
 * 
 */
interface FieldsMapPublicSerializationMixIn {
	@JsonProperty("@types")
	@JsonInclude(Include.NON_EMPTY)
	Map<String, FieldBaseTypes> getTypes();

	@JsonAnyGetter
	Map<String, Object> getAll();

}