package org.apache.olingo.jpa.metadata.core.edm.mapper.impl;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.AttributeConverter;
import javax.persistence.JoinColumn;
import javax.persistence.JoinColumns;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.Attribute.PersistentAttributeType;
import javax.persistence.metamodel.PluralAttribute;

import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.provider.CsdlNavigationProperty;
import org.apache.olingo.jpa.metadata.core.edm.annotation.EdmIgnore;
import org.apache.olingo.jpa.metadata.core.edm.mapper.api.JPAAssociationAttribute;
import org.apache.olingo.jpa.metadata.core.edm.mapper.api.JPAStructuredType;
import org.apache.olingo.jpa.metadata.core.edm.mapper.exception.ODataJPAModelException;
import org.apache.olingo.jpa.metadata.core.edm.mapper.extention.IntermediateNavigationPropertyAccess;

/**
 * <a
 * href=
 * "http://docs.oasis-open.org/odata/odata/v4.0/errata02/os/complete/part3-csdl/odata-v4.0-errata02-os-part3-csdl-complete.html#_Toc406397962"
 * >OData Version 4.0 Part 3 - 7 Navigation Property</a>
 * @author Oliver Grande
 *
 */
class IntermediateNavigationProperty extends IntermediateModelElement implements IntermediateNavigationPropertyAccess,
    JPAAssociationAttribute {

  private final Attribute<?, ?> jpaAttribute;
  private CsdlNavigationProperty edmNaviProperty;
  private final IntermediateStructuredType sourceType;
  private IntermediateStructuredType targetType;
  private final IntermediateSchema schema;
  private final List<IntermediateJoinColumn> joinColumns = new ArrayList<IntermediateJoinColumn>();

  IntermediateNavigationProperty(final JPAEdmNameBuilder nameBuilder, final IntermediateStructuredType parent,
      final Attribute<?, ?> jpaAttribute, final IntermediateSchema schema) {
    super(nameBuilder, IntNameBuilder.buildAssociationName(jpaAttribute));
    this.jpaAttribute = jpaAttribute;
    this.schema = schema;
    this.sourceType = parent;
    buildNaviProperty();

  }

  @Override
  protected void lazyBuildEdmItem() throws ODataJPAModelException {
    if (edmNaviProperty == null) {
      String mappedBy = null;
      boolean isSourceOne = false;
      edmNaviProperty = new CsdlNavigationProperty();
      edmNaviProperty.setName(getExternalName());
      edmNaviProperty.setType(nameBuilder.buildFQN(targetType.getExternalName()));
      edmNaviProperty.setCollection(jpaAttribute.isCollection());
      // Optional --> ReleationAnnotation
      if (jpaAttribute.getJavaMember() instanceof AnnotatedElement) {
        final AnnotatedElement annotatedElement = (AnnotatedElement) jpaAttribute.getJavaMember();
        switch (jpaAttribute.getPersistentAttributeType()) {
        case ONE_TO_MANY:
          final OneToMany cardinalityOtM = annotatedElement.getAnnotation(OneToMany.class);
          mappedBy = cardinalityOtM.mappedBy();
          isSourceOne = true;
          break;
        case ONE_TO_ONE:
          final OneToOne cardinalityOtO = annotatedElement.getAnnotation(OneToOne.class);
          edmNaviProperty.setNullable(cardinalityOtO.optional());
          mappedBy = cardinalityOtO.mappedBy();
          isSourceOne = true;
          break;
        case MANY_TO_ONE:
          final ManyToOne cardinalityMtO = annotatedElement.getAnnotation(ManyToOne.class);
          edmNaviProperty.setNullable(cardinalityMtO.optional());
          break;
        default:
          break;
        }

        int implicitColumns = 0;
        final JoinColumns columns = annotatedElement.getAnnotation(JoinColumns.class);
        if (columns != null) {
          for (final JoinColumn column : columns.value()) {
            final IntermediateJoinColumn intermediateColumn = new IntermediateJoinColumn(column);
            final String refColumnName = intermediateColumn.getReferencedColumnName();
            final String name = intermediateColumn.getName();
            if (refColumnName == null || refColumnName.isEmpty() ||
                name == null || name.isEmpty()) {
              implicitColumns += 1;
              if (implicitColumns > 1)
                throw ODataJPAModelException.throwException(
                    ODataJPAModelException.NOT_SUPPORTED_NO_IMPLICIT_COLUMNS,
                    "Relationship " + getInternalName() + ": Only one implicit column name supported");
              fillMissingName(isSourceOne, intermediateColumn);
            }
            joinColumns.add(intermediateColumn);
          }
        }

        else {
          final JoinColumn column = annotatedElement.getAnnotation(JoinColumn.class);
          if (column != null) {
            final IntermediateJoinColumn intermediateColumn = new IntermediateJoinColumn(column);
            fillMissingName(isSourceOne, intermediateColumn);
            joinColumns.add(intermediateColumn);

          } else if (mappedBy != null && !mappedBy.isEmpty()) {
            joinColumns.addAll(targetType.getJoinColumns(sourceType, getInternalName()));
            for (final IntermediateJoinColumn intermediateColumn : joinColumns) {
              final String refColumnName = intermediateColumn.getReferencedColumnName();
              if (refColumnName == null || refColumnName.isEmpty()) {
                implicitColumns += 1;
                if (implicitColumns > 1)
                  throw ODataJPAModelException.throwException(
                      ODataJPAModelException.NOT_SUPPORTED_NO_IMPLICIT_COLUMNS,
                      "Relationship " + getInternalName() + ": Only one implicit column name supported");
                intermediateColumn.setReferencedColumnName(((IntermediateProperty) ((IntermediateEntityType) sourceType)
                    .getKey().get(0)).getDBFieldName());
              }
            }
          }
        }
      }
      // TODO determine ContainsTarget
      if (sourceType instanceof IntermediateEntityType) {
        // Partner Attribute must not be defined at Complex Types.
        // JPA bi-directional associations are defined at both sides, e.g.
        // at the BusinessPartner and at the Roles. JPA only defines the
        // "mappedBy" at the Parent.
        if (mappedBy != null && !mappedBy.isEmpty()) {
          edmNaviProperty.setPartner(targetType.getCorrespondingNavigationProperty(sourceType, getInternalName())
              .getExternalName());
        } else {
          final IntermediateNavigationProperty partner = targetType.getCorrespondingNavigationProperty(sourceType,
              getInternalName());
          if (partner != null) {
            if (partner.isMapped())
              edmNaviProperty.setPartner(partner.getExternalName());
          }
        }
      }
      // TODO determine ReferentialConstraint
      // TODO determine OnDelete --> From Cascade?

    }

  }

  boolean isMapped() {
    if (jpaAttribute.getPersistentAttributeType() == PersistentAttributeType.ONE_TO_ONE) {
      final AnnotatedElement annotatedElement = (AnnotatedElement) jpaAttribute.getJavaMember();
      final OneToOne cardinalityOtO = annotatedElement.getAnnotation(OneToOne.class);
      return cardinalityOtO.mappedBy() != null && !cardinalityOtO.mappedBy().isEmpty() ? true : false;
    }
    if (jpaAttribute.getPersistentAttributeType() == PersistentAttributeType.ONE_TO_MANY) {
      final AnnotatedElement annotatedElement = (AnnotatedElement) jpaAttribute.getJavaMember();
      final OneToMany cardinalityOtM = annotatedElement.getAnnotation(OneToMany.class);
      return cardinalityOtM.mappedBy() != null && !cardinalityOtM.mappedBy().isEmpty() ? true : false;
    }
    return false;
  }

  private void fillMissingName(final boolean isSourceOne, final IntermediateJoinColumn intermediateColumn)
      throws ODataJPAModelException {

    final String refColumnName = intermediateColumn.getReferencedColumnName();
    final String name = intermediateColumn.getName();

    if (isSourceOne && (refColumnName == null || refColumnName.isEmpty()))
      intermediateColumn.setReferencedColumnName(((IntermediateProperty) ((IntermediateEntityType) sourceType)
          .getKey().get(0)).getDBFieldName());
    else if (isSourceOne && (name == null || name.isEmpty()))
      intermediateColumn.setReferencedColumnName(((IntermediateProperty) ((IntermediateEntityType) targetType)
          .getKey().get(0)).getDBFieldName());
    else if (!isSourceOne && (refColumnName == null || refColumnName.isEmpty()))
      intermediateColumn.setReferencedColumnName(((IntermediateProperty) ((IntermediateEntityType) targetType)
          .getKey().get(0)).getDBFieldName());
    else if (!isSourceOne && (name == null || name.isEmpty()))
      intermediateColumn.setReferencedColumnName(((IntermediateProperty) ((IntermediateEntityType) sourceType)
          .getKey().get(0)).getDBFieldName());
  }

  @Override
      CsdlNavigationProperty getEdmItem() throws ODataJPAModelException {
    lazyBuildEdmItem();
    return edmNaviProperty;
  }

  private void buildNaviProperty() {
    this.setExternalName(nameBuilder.buildNaviPropertyName(jpaAttribute));
    Class<?> targetClass = null;
    if (jpaAttribute.isCollection()) {
      targetClass = ((PluralAttribute<?, ?, ?>) jpaAttribute).getElementType().getJavaType();
    } else {
      targetClass = jpaAttribute.getJavaType();
    }
    if (this.jpaAttribute.getJavaMember() instanceof AnnotatedElement) {
      final EdmIgnore jpaIgnore = ((AnnotatedElement) this.jpaAttribute.getJavaMember()).getAnnotation(
          EdmIgnore.class);
      if (jpaIgnore != null) {
        this.setIgnore(true);
      }
    }

    targetType = schema.getEntityType(targetClass);
    postProcessor.processNavigationProperty(this, jpaAttribute.getDeclaringType().getJavaType()
        .getCanonicalName());
  }

  @Override
  public AttributeConverter<?, ?> getConverter() {
    return null;
  }

  @Override
  public JPAStructuredType getStructuredType() {
    return null;
  }

  @Override
  public Class<?> getType() {
    return jpaAttribute.getJavaType();
  }

  @Override
  public boolean isComplex() {
    return false;
  }

  @Override
  public boolean isKey() {
    return false;
  }

  @Override
  public boolean isAssociation() {
    return true;
  }

  @Override
  public JPAStructuredType getTargetEntity() throws ODataJPAModelException {
    lazyBuildEdmItem();
    return targetType;
  }

  @Override
  public EdmPrimitiveTypeKind getEdmType() {
    return null;
  }

  List<IntermediateJoinColumn> getJoinColumns() throws ODataJPAModelException {
    lazyBuildEdmItem();
    return joinColumns;
  }

  PersistentAttributeType getJoinCardinality() throws ODataJPAModelException {
    return jpaAttribute.getPersistentAttributeType();
  }

  @Override
  public boolean isCollection() {
    return jpaAttribute.isCollection();
  }

  @Override
  public CsdlNavigationProperty getProperty() throws ODataJPAModelException {
    return getEdmItem();
  }
}
