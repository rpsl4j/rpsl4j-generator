/*
 * Copyright (c) 2013 RIPE NCC
 * All rights reserved.
 */

package net.ripe.db.whois.common.rpsl;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import net.ripe.db.whois.common.domain.CIString;
import org.apache.commons.lang.Validate;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Immutable
public class RpslObject {
    private final ObjectType type;
    private final RpslAttribute typeAttribute;
    private final CIString key;

    private List<RpslAttribute> attributes;
    private Map<AttributeType, List<RpslAttribute>> typeCache;
    private int hash;

    public RpslObject(final RpslObject oldObject, final List<RpslAttribute> attributes) {
        this(attributes);
    }

    public RpslObject(final List<RpslAttribute> attributes) {
        Validate.notEmpty(attributes);

        this.typeAttribute = attributes.get(0);
        this.type = ObjectType.getByName(typeAttribute.getKey());
        this.attributes = Collections.unmodifiableList(attributes);

        final Set<AttributeType> keyAttributes = ObjectTemplate.getTemplate(type).getKeyAttributes();
        if (keyAttributes.size() == 1) {
            this.key = getValueForAttribute(keyAttributes.iterator().next());
            Validate.notEmpty(this.key.toString(), "key attributes must have value");
        } else {
            final StringBuilder keyBuilder = new StringBuilder(32);
            for (AttributeType keyAttribute : keyAttributes) {
                String key = getValueForAttribute(keyAttribute).toString();
                Validate.notEmpty(key, "key attributes must have value");
                keyBuilder.append(key);
            }
            this.key = CIString.ciString(keyBuilder.toString());
        }
    }

    public static RpslObject parse(final String input) {
        return new RpslObject(RpslObjectBuilder.getAttributes(input));
    }

    public static RpslObject parse(final byte[] input) {
        return new RpslObject(RpslObjectBuilder.getAttributes(input));
    }

    public ObjectType getType() {
        return type;
    }

    public List<RpslAttribute> getAttributes() {
        return attributes;
    }

    public int size() {
        return attributes.size();
    }

    public final CIString getKey() {
        return key;
    }

    public String getFormattedKey() {
        switch (type) {
            case PERSON:
            case ROLE:
                return String.format("[%s] %s   %s", type.getName(), getKey(), getAttributes().get(0).getCleanValue());
            default:
                return String.format("[%s] %s", type.getName(), getKey());
        }
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof RpslObject)) {
            return false;
        }

        final RpslObject other = (RpslObject) obj;
        return Iterables.elementsEqual(getAttributes(), other.getAttributes());
    }

    @Override
    public int hashCode() {
        if (hash == 0) {
            int result = getAttributes().hashCode();
            if (result == 0) {
                result--;
            }
            hash = result;
        }

        return hash;
    }

    public RpslAttribute getTypeAttribute() {
        return getAttributes().get(0);
    }

    Map<AttributeType, List<RpslAttribute>> getOrCreateCache() {
        if (typeCache == null) {
            final EnumMap<AttributeType, List<RpslAttribute>> map = Maps.newEnumMap(AttributeType.class);

            for (final RpslAttribute attribute : getAttributes()) {
                final AttributeType attributeType = attribute.getType();
                if (attributeType == null) {
                    continue;
                }

                List<RpslAttribute> list = map.get(attributeType);
                if (list == null) {
                    list = Lists.newArrayList();
                    map.put(attributeType, list);
                }

                list.add(attribute);
            }

            typeCache = map;
        }

        return typeCache;
    }

    public RpslAttribute findAttribute(final AttributeType attributeType) {
        final List<RpslAttribute> foundAttributes = findAttributes(attributeType);
        switch (foundAttributes.size()) {
            case 0:
                throw new IllegalArgumentException("No " + attributeType + ": found in " + key);
            case 1:
                return foundAttributes.get(0);
            default:
                throw new IllegalArgumentException("Multiple " + attributeType + ": found in " + key);
        }
    }

    public List<RpslAttribute> findAttributes(final AttributeType attributeType) {
        final List<RpslAttribute> list = getOrCreateCache().get(attributeType);
        return list == null ? Collections.<RpslAttribute>emptyList() : Collections.unmodifiableList(list);
    }

    public List<RpslAttribute> findAttributes(final Iterable<AttributeType> attributeTypes) {
        final List<RpslAttribute> result = Lists.newArrayList();

        for (final AttributeType attributeType : attributeTypes) {
            findCachedAttributes(result, attributeType);
        }

        return result;
    }

    public List<RpslAttribute> findAttributes(final AttributeType... attributeTypes) {
        return findAttributes(Arrays.asList(attributeTypes));
    }

    private void findCachedAttributes(final List<RpslAttribute> result, final AttributeType attributeType) {
        final List<RpslAttribute> list = getOrCreateCache().get(attributeType);
        if (list != null) {
            result.addAll(list);
        }
    }

    public boolean containsAttributes(final Collection<AttributeType> attributeTypes) {
        for (AttributeType attributeType : attributeTypes) {
            if (containsAttribute(attributeType)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsAttribute(final AttributeType attributeType) {
        return getOrCreateCache().containsKey(attributeType);
    }

    public void writeTo(final Writer writer) throws IOException {
        for (final RpslAttribute attribute : getAttributes()) {
            attribute.writeTo(writer);
        }

        writer.flush();
    }

    @Override
    public String toString() {
        try {
            final StringWriter writer = new StringWriter();
            for (final RpslAttribute attribute : getAttributes()) {
                attribute.writeTo(writer);
            }

            return writer.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Should never occur", e);
        }
    }

    public CIString getValueForAttribute(final AttributeType attributeType) {
        return findAttribute(attributeType).getCleanValue();
    }

    @Nullable
    public CIString getValueOrNullForAttribute(final AttributeType attributeType) {
        List<RpslAttribute> attributes = findAttributes(attributeType);
        if (attributes.isEmpty()) {
            return null;
        }
        return attributes.get(0).getCleanValue();
    }

    public Set<CIString> getValuesForAttribute(final AttributeType... attributeType) {
        final Set<CIString> values = Sets.newLinkedHashSet();
        for (AttributeType attrType : attributeType) {
            final List<RpslAttribute> rpslAttributes = getOrCreateCache().get(attrType);

            if (!rpslAttributes.isEmpty()) {
                for (RpslAttribute rpslAttribute : rpslAttributes) {
                    values.addAll(rpslAttribute.getCleanValues());
                }
            }
        }
        return values;
    }
}