/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html.
 */
package org.hibernate.testing.annotations.methods;

import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.engine.config.spi.ConfigurationService;

import org.hibernate.testing.annotations.AnEntity;
import org.hibernate.testing.annotations.AnotherEntity;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.DomainModelScope;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Steve Ebersole
 */
@ServiceRegistry( settings = @Setting( name = "simple", value = "simple-value"))
@DomainModel( annotatedClasses = AnEntity.class )
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DomainModelTesting {
	private int executingTestOrder;

	@Test
	@Order( 2 )
	public void testClassLevelAnnotations(DomainModelScope scope) {
		executingTestOrder = 2;
		classLevelAssertions( scope );
	}

	private void classLevelAssertions(DomainModelScope scope) {
		assertThat( scope.getDomainModel().getEntityBinding( AnEntity.class.getName() ) ).isNotNull();
		assertThat( scope.getDomainModel().getEntityBinding( AnotherEntity.class.getName() ) ).isNull();

		settingAssertions( scope );
	}

	private void settingAssertions(DomainModelScope scope) {
		final org.hibernate.service.ServiceRegistry serviceRegistry = ( (MetadataImplementor) scope.getDomainModel() )
				.getTypeConfiguration()
				.getServiceRegistry();
		final ConfigurationService configurationService = serviceRegistry.getService( ConfigurationService.class );
		assertThat( configurationService.getSettings().get( "simple" ) ).isEqualTo( "simple-value" );
	}

	@Test
	@DomainModel( annotatedClasses = AnotherEntity.class )
	@Order( 1 )
	public void testMethodLevelAnnotations(DomainModelScope scope) {
		executingTestOrder = 1;
		methodLevelAssertions( scope );
	}

	private void methodLevelAssertions(DomainModelScope scope) {
		assertThat( scope.getDomainModel().getEntityBinding( AnEntity.class.getName() ) ).isNull();
		assertThat( scope.getDomainModel().getEntityBinding( AnotherEntity.class.getName() ) ).isNotNull();
		settingAssertions( scope );
	}

	@AfterEach
	public void doStuffAfter(DomainModelScope scope) {
		if ( executingTestOrder == 1 ) {
			methodLevelAssertions( scope );
		}
		else if ( executingTestOrder == 2 ) {
			classLevelAssertions( scope );
		}
		else {
			throw new RuntimeException( "boom" );
		}
	}
}
