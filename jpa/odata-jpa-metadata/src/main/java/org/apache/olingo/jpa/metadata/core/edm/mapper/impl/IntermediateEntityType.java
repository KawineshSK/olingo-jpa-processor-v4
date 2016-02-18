package org.apache.olingo.jpa.metadata.core.edm.mapper.impl;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.IdentifiableType;

import org.apache.olingo.commons.api.edm.provider.CsdlEntityType;
import org.apache.olingo.commons.api.edm.provider.CsdlNavigationProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlPropertyRef;
import org.apache.olingo.jpa.metadata.core.edm.annotation.EdmIgnore;
import org.apache.olingo.jpa.metadata.core.edm.mapper.api.JPAAttribute;
import org.apache.olingo.jpa.metadata.core.edm.mapper.api.JPAEntityType;
import org.apache.olingo.jpa.metadata.core.edm.mapper.api.JPAPath;
import org.apache.olingo.jpa.metadata.core.edm.mapper.exception.ODataJPAModelException;

/**
 * <a href=
 * "https://docs.oasis-open.org/odata/odata/v4.0/errata02/os/complete/part3-csdl/odata-v4.0-errata02-os-part3-csdl-complete.html#_Toc406397974"
 * >OData Version 4.0 Part 3 - 8 Entity Type</a>
 * @author Oliver Grande
 *
 */
class IntermediateEntityType extends IntermediateStructuredType implements JPAEntityType {
  CsdlEntityType edmEntityType;

  IntermediateEntityType(final JPAEdmNameBuilder nameBuilder, final EntityType<?> et, final IntermediateSchema schema)
      throws ODataJPAModelException {
    super(nameBuilder, et, schema);
    this.setExternalName(nameBuilder.buildEntityTypeName(et));
    final EdmIgnore jpaIgnore = ((AnnotatedElement) this.jpaManagedType.getJavaType()).getAnnotation(
        EdmIgnore.class);
    if (jpaIgnore != null) {
      this.setIgnore(true);
    }
  }

  @Override
  public List<? extends JPAAttribute> getKey() throws ODataJPAModelException {
    lazyBuildEdmItem();
    final List<JPAAttribute> key = new ArrayList<JPAAttribute>();

    for (final String internalName : this.declaredPropertiesList.keySet()) {
      final JPAAttribute attribute = this.declaredPropertiesList.get(internalName);
      if (attribute.isKey())
        key.add(attribute);
    }
    final IntermediateStructuredType baseType = getBaseType();
    if (baseType != null) {
      final Map<String, JPAPathImpl> baseAttributes = baseType.getResolvedPathMap();
      for (final String baseExternalName : baseAttributes.keySet()) {
        final JPAPath baseAttributePath = baseAttributes.get(baseExternalName);
        // TODO Embbeded Ids!!
        final JPAAttribute baseAttribute = (JPAAttribute) baseAttributePath.getPath().get(0);
        if (baseAttribute.isKey())
          key.add(baseAttribute);
      }
    }
    return key;
  }

  @Override
      CsdlEntityType getEdmItem() throws ODataJPAModelException {
    lazyBuildEdmItem();
    return edmEntityType;
  }

  @SuppressWarnings("unchecked")
  @Override
  protected void lazyBuildEdmItem() throws ODataJPAModelException {
    if (edmEntityType == null) {
      // TODO store @Version to fill ETag Header
      buildPropertyList();
      edmEntityType = new CsdlEntityType();
      edmEntityType.setName(getExternalName());
      edmEntityType.setProperties((List<CsdlProperty>) extractEdmModelElements(declaredPropertiesList));
      edmEntityType.setNavigationProperties((List<CsdlNavigationProperty>) extractEdmModelElements(
          declaredNaviPropertiesList));
      edmEntityType.setKey(extractEdmKeyElements(declaredPropertiesList));
      // TODO check: An abstract entity type MUST NOT inherit from a non-abstract entity type.
      edmEntityType.setAbstract(determineAbstract());
      edmEntityType.setBaseType(determineBaseType());
      // TODO determine OpenType
      // TODO determine HasStream
    }
  }

  boolean determineAbstract() {
    final int modifiers = jpaManagedType.getJavaType().getModifiers();
    return Modifier.isAbstract(modifiers);
  }

  List<CsdlPropertyRef> extractEdmKeyElements(final Map<String, IntermediateProperty> propertyList)
      throws ODataJPAModelException {

    final List<CsdlPropertyRef> keyList = new ArrayList<CsdlPropertyRef>();
    for (final String internalName : propertyList.keySet()) {
      if (propertyList.get(internalName).isKey()) {
        if (propertyList.get(internalName).isComplex())
          // TODO Clarify if it is correct that OData or Olingo do not support complex Types as key
          throw ODataJPAModelException.throwException(ODataJPAModelException.NOT_SUPPORTED_EMBEDDED_KEY,
              "Embedded Ids are not supported");
        final CsdlPropertyRef key = new CsdlPropertyRef();
        key.setName(propertyList.get(internalName).getExternalName());
        // TODO setAlias
        keyList.add(key);
      }
    }
    return returnNullIfEmpty(keyList);
  }

  @Override
  public List<JPAPath> searchChildPath(final JPAPath selectItemPath) {
    final List<JPAPath> result = new ArrayList<JPAPath>();
    for (final String pathName : this.resolvedPathMap.keySet()) {
      final JPAPath p = resolvedPathMap.get(pathName);
      // if (p.getPath().get(0) == selectItemPath.getPath().get(0))
      if (!p.ignore() && p.getAlias().startsWith(selectItemPath.getAlias()))
        result.add(p);
    }
    return result;
  }

  @Override
  public Class<?> getKeyType() {
    if (jpaManagedType instanceof IdentifiableType<?>)
      return ((IdentifiableType<?>) jpaManagedType).getIdType().getJavaType();
    else
      return null;
  }

}
