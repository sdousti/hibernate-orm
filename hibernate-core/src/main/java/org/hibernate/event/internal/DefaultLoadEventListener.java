/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.event.internal;

import java.util.List;

import org.hibernate.HibernateException;
import org.hibernate.LockMode;
import org.hibernate.NonUniqueObjectException;
import org.hibernate.PersistentObjectException;
import org.hibernate.TypeMismatchException;
import org.hibernate.action.internal.DelayedPostInsertIdentifier;
import org.hibernate.cache.spi.access.EntityDataAccess;
import org.hibernate.cache.spi.access.SoftLock;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.Status;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.LoadEvent;
import org.hibernate.event.spi.LoadEventListener;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.loader.entity.CacheEntityLoaderHelper;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.CompositeIdentifierMapping;
import org.hibernate.metamodel.mapping.EntityIdentifierMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.MappingType;
import org.hibernate.metamodel.mapping.NonAggregatedIdentifierMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.pretty.MessageHelper;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.stat.spi.StatisticsImplementor;
import org.hibernate.tuple.entity.EntityMetamodel;

/**
 * Defines the default load event listeners used by hibernate for loading entities
 * in response to generated load events.
 *
 * @author Steve Ebersole
 */
public class DefaultLoadEventListener implements LoadEventListener {

	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( DefaultLoadEventListener.class );

	/**
	 * Handle the given load event.
	 *
	 * @param event The load event to be handled.
	 */
	public void onLoad(
			final LoadEvent event,
			final LoadType loadType) throws HibernateException {

		final EntityPersister persister = getPersister( event );

		if ( persister == null ) {
			throw new HibernateException( "Unable to locate persister: " + event.getEntityClassName() );
		}

		if ( !persister.getIdentifierMapping().getJavaType().isInstance( event.getEntityId() ) &&
				!( event.getEntityId() instanceof DelayedPostInsertIdentifier ) ) {
			checkIdClass( persister, event, loadType, persister.getIdentifierType().getReturnedClass() );
		}

		doOnLoad( persister, event, loadType );
	}

	protected EntityPersister getPersister(final LoadEvent event) {
		final Object instanceToLoad = event.getInstanceToLoad();
		if ( instanceToLoad != null ) {
			//the load() which takes an entity does not pass an entityName
			event.setEntityClassName( instanceToLoad.getClass().getName() );
			return event.getSession().getEntityPersister(
					null,
					instanceToLoad
			);
		}
		else {
			return event.getSession()
					.getFactory()
					.getRuntimeMetamodels()
					.getMappingMetamodel()
					.getEntityDescriptor( event.getEntityClassName() );
		}
	}

	private void doOnLoad(
			final EntityPersister persister,
			final LoadEvent event,
			final LoadType loadType) {

		try {
			final EventSource session = event.getSession();
			final EntityKey keyToLoad = session.generateEntityKey( event.getEntityId(), persister );
			if ( loadType.isNakedEntityReturned() ) {
				//do not return a proxy!
				//(this option indicates we are initializing a proxy)
				event.setResult( load( event, persister, keyToLoad, loadType ) );
			}
			else {
				//return a proxy if appropriate
				if ( event.getLockMode() == LockMode.NONE ) {
					event.setResult( proxyOrLoad( event, persister, keyToLoad, loadType ) );
				}
				else {
					event.setResult( lockAndLoad( event, persister, keyToLoad, loadType, session ) );
				}
			}
		}
		catch (HibernateException e) {
			LOG.unableToLoadCommand( e );
			throw e;
		}
	}

	private void checkIdClass(
			final EntityPersister persister,
			final LoadEvent event,
			final LoadType loadType,
			final Class<?> idClass) {
		// we may have the jpa requirement of allowing find-by-id where id is the "simple pk value" of a
		// dependent objects parent.  This is part of its generally goofy derived identity "feature"
		final EntityIdentifierMapping idMapping = persister.getIdentifierMapping();
		if ( idMapping instanceof CompositeIdentifierMapping ) {
			final CompositeIdentifierMapping compositeIdMapping = (CompositeIdentifierMapping) idMapping;
			final List<AttributeMapping> attributeMappings = compositeIdMapping.getPartMappingType().getAttributeMappings();
			if ( attributeMappings.size() == 1 ) {
				final AttributeMapping singleIdAttribute = attributeMappings.get( 0 );
				if ( singleIdAttribute.getMappedType() instanceof EntityMappingType ) {
					final EntityMappingType parentIdTargetMapping = (EntityMappingType) singleIdAttribute.getMappedType();
					final EntityIdentifierMapping parentIdTargetIdMapping = parentIdTargetMapping.getIdentifierMapping();
					final MappingType parentIdType = parentIdTargetIdMapping instanceof CompositeIdentifierMapping
							? ((CompositeIdentifierMapping) parentIdTargetIdMapping).getMappedIdEmbeddableTypeDescriptor()
							: parentIdTargetIdMapping.getMappedType();
					if ( parentIdType.getMappedJavaType().getJavaTypeClass().isInstance( event.getEntityId() ) ) {
						// yep that's what we have...
						loadByDerivedIdentitySimplePkValue(
								event,
								loadType,
								persister,
								compositeIdMapping,
								(EntityPersister) parentIdTargetMapping
						);
						return;
					}
				}
				else if ( idClass.isInstance( event.getEntityId() ) ) {
					return;
				}
			}
			else if ( idMapping instanceof NonAggregatedIdentifierMapping ) {
				if ( idClass.isInstance( event.getEntityId() ) ) {
					return;
				}
			}
		}

		throw new TypeMismatchException(
				"Provided id of the wrong type for class " + persister.getEntityName()
						+ ". Expected: " + idClass
						+ ", got " + event.getEntityId().getClass()
		);
	}

	private void loadByDerivedIdentitySimplePkValue(
			LoadEvent event,
			LoadType options,
			EntityPersister dependentPersister,
			CompositeIdentifierMapping dependentIdType,
			EntityPersister parentPersister) {
		final EventSource session = event.getSession();
		final EntityKey parentEntityKey = session.generateEntityKey( event.getEntityId(), parentPersister );
		final Object parent = doLoad( event, parentPersister, parentEntityKey, options );

		final Object dependent = dependentIdType.instantiate();
		dependentIdType.getPartMappingType().setValues( dependent, new Object[] { parent } );
		final EntityKey dependentEntityKey = session.generateEntityKey( dependent, dependentPersister );
		event.setEntityId( dependent );

		event.setResult( doLoad( event, dependentPersister, dependentEntityKey, options ) );
	}

	/**
	 * Performs the load of an entity.
	 *
	 * @param event The initiating load request event
	 * @param persister The persister corresponding to the entity to be loaded
	 * @param keyToLoad The key of the entity to be loaded
	 * @param options The defined load options
	 *
	 * @return The loaded entity.
	 */
	private Object load(
			final LoadEvent event,
			final EntityPersister persister,
			final EntityKey keyToLoad,
			final LoadType options) {

		final EventSource session = event.getSession();
		if ( event.getInstanceToLoad() != null ) {
			if ( session.getPersistenceContextInternal().getEntry( event.getInstanceToLoad() ) != null ) {
				throw new PersistentObjectException(
						"attempted to load into an instance that was already associated with the session: " +
								MessageHelper.infoString(
										persister,
										event.getEntityId(),
										session.getFactory()
								)
				);
			}
			persister.setIdentifier( event.getInstanceToLoad(), event.getEntityId(), session);
		}

		final Object entity = doLoad( event, persister, keyToLoad, options );

		boolean isOptionalInstance = event.getInstanceToLoad() != null;

		if ( entity == null && ( !options.isAllowNulls() || isOptionalInstance ) ) {
			session.getFactory()
					.getEntityNotFoundDelegate()
					.handleEntityNotFound( event.getEntityClassName(), event.getEntityId() );
		}
		else if ( isOptionalInstance && entity != event.getInstanceToLoad() ) {
			throw new NonUniqueObjectException( event.getEntityId(), event.getEntityClassName() );
		}

		return entity;
	}

	/**
	 * Based on configured options, will either return a pre-existing proxy,
	 * generate a new proxy, or perform an actual load.
	 *
	 * @param event The initiating load request event
	 * @param persister The persister corresponding to the entity to be loaded
	 * @param keyToLoad The key of the entity to be loaded
	 * @param options The defined load options
	 *
	 * @return The result of the proxy/load operation.
	 */
	private Object proxyOrLoad(
			final LoadEvent event,
			final EntityPersister persister,
			final EntityKey keyToLoad,
			final LoadType options) {

		final EventSource session = event.getSession();
		final SessionFactoryImplementor factory = session.getFactory();
		final boolean traceEnabled = LOG.isTraceEnabled();

		if ( traceEnabled ) {
			LOG.tracev(
					"Loading entity: {0}",
					MessageHelper.infoString( persister, event.getEntityId(), factory )
			);
		}

		final PersistenceContext persistenceContext = session.getPersistenceContextInternal();

		final EntityMetamodel entityMetamodel = persister.getEntityMetamodel();

		// Check for the case where we can use the entity itself as a proxy
		if ( options.isAllowProxyCreation()
				&& entityMetamodel.getBytecodeEnhancementMetadata().isEnhancedForLazyLoading() ) {
			// if there is already a managed entity instance associated with the PC, return it
			final Object managed = persistenceContext.getEntity( keyToLoad );
			if ( managed != null ) {
				if ( options.isCheckDeleted() ) {
					final Status status = persistenceContext.getEntry( managed ).getStatus();
					if ( status == Status.DELETED || status == Status.GONE ) {
						return null;
					}
				}
				return managed;
			}

			// if the entity defines a HibernateProxy factory, see if there is an
			// existing proxy associated with the PC - and if so, use it
			if ( persister.getRepresentationStrategy().getProxyFactory() != null ) {
				final Object proxy = persistenceContext.getProxy( keyToLoad );

				if ( proxy != null ) {
					if( traceEnabled ) {
						LOG.trace( "Entity proxy found in session cache" );
					}

					if ( LOG.isDebugEnabled() && ( (HibernateProxy) proxy ).getHibernateLazyInitializer().isUnwrap() ) {
						LOG.debug( "Ignoring NO_PROXY to honor laziness" );
					}

					return persistenceContext.narrowProxy( proxy, persister, keyToLoad, null );
				}

				// specialized handling for entities with subclasses with a HibernateProxy factory
				if ( entityMetamodel.hasSubclasses() ) {
					final Object cachedEntity = CacheEntityLoaderHelper.INSTANCE.loadFromSecondLevelCache(
							session,
							null,
							LockMode.NONE,
							persister,
							keyToLoad
					);

					if ( cachedEntity != null ) {
						return cachedEntity;
					}
					// entities with subclasses that define a ProxyFactory can create a HibernateProxy
					return createProxy( event, persister, keyToLoad, persistenceContext );
				}
			}
			if ( !entityMetamodel.hasSubclasses() ) {
				if ( keyToLoad.isBatchLoadable() ) {
					// Add a batch-fetch entry into the queue for this entity
					persistenceContext.getBatchFetchQueue().addBatchLoadableEntityKey( keyToLoad );
				}

				// This is the crux of HHH-11147
				// create the (uninitialized) entity instance - has only id set
				return persister.getBytecodeEnhancementMetadata()
						.createEnhancedProxy( keyToLoad, true, session );
			}
			// If we get here, then the entity class has subclasses and there is no HibernateProxy factory.
			// The entity will get loaded below.
		}
		else {
			if ( persister.hasProxy() ) {
				// look for a proxy
				Object proxy = persistenceContext.getProxy( keyToLoad );
				if ( proxy != null ) {
					return returnNarrowedProxy( event, persister, keyToLoad, options, persistenceContext, proxy );
				}

				if ( options.isAllowProxyCreation() ) {
					if ( entityMetamodel.hasSubclasses() ) {
						final Object cachedEntity = CacheEntityLoaderHelper.INSTANCE.loadFromSecondLevelCache(
								session,
								null,
								LockMode.NONE,
								persister,
								keyToLoad
						);

						if ( cachedEntity != null ) {
							return cachedEntity;
						}
					}
					return createProxyIfNecessary( event, persister, keyToLoad, options, persistenceContext );
				}
			}
		}

		// return a newly loaded object
		return load( event, persister, keyToLoad, options );
	}


	/**
	 * Given a proxy, initialize it and/or narrow it provided either
	 * is necessary.
	 *
	 * @param event The initiating load request event
	 * @param persister The persister corresponding to the entity to be loaded
	 * @param keyToLoad The key of the entity to be loaded
	 * @param options The defined load options
	 * @param persistenceContext The originating session
	 * @param proxy The proxy to narrow
	 *
	 * @return The created/existing proxy
	 */
	private Object returnNarrowedProxy(
			final LoadEvent event,
			final EntityPersister persister,
			final EntityKey keyToLoad,
			final LoadType options,
			final PersistenceContext persistenceContext,
			final Object proxy) {
		if ( LOG.isTraceEnabled() ) {
			LOG.trace( "Entity proxy found in session cache" );
		}

		LazyInitializer li = ( (HibernateProxy) proxy ).getHibernateLazyInitializer();
		if ( li.isUnwrap() ) {
			return li.getImplementation();
		}

		Object impl = null;
		if ( !options.isAllowProxyCreation() ) {
			impl = load( event, persister, keyToLoad, options );
			if ( impl == null ) {
				if ( options == LoadEventListener.INTERNAL_LOAD_NULLABLE ) {
					// The proxy is for a non-existing association mapped as @NotFound.
					// Don't throw an exeption; just return null.
					return null;
				}
				else {
					event.getSession()
							.getFactory()
							.getEntityNotFoundDelegate()
							.handleEntityNotFound( persister.getEntityName(), keyToLoad.getIdentifier() );
				}
			}
		}

		return persistenceContext.narrowProxy( proxy, persister, keyToLoad, impl );
	}

	/**
	 * If there is already a corresponding proxy associated with the
	 * persistence context, return it; otherwise create a proxy, associate it
	 * with the persistence context, and return the just-created proxy.
	 *
	 * @param event The initiating load request event
	 * @param persister The persister corresponding to the entity to be loaded
	 * @param keyToLoad The key of the entity to be loaded
	 * @param options The defined load options
	 * @param persistenceContext The originating session
	 *
	 * @return The created/existing proxy
	 */
	private Object createProxyIfNecessary(
			final LoadEvent event,
			final EntityPersister persister,
			final EntityKey keyToLoad,
			final LoadType options,
			final PersistenceContext persistenceContext) {
		Object existing = persistenceContext.getEntity( keyToLoad );
		if ( existing != null ) {
			// return existing object or initialized proxy (unless deleted)
			if ( LOG.isTraceEnabled() ) {
				LOG.trace( "Entity found in session cache" );
			}
			if ( options.isCheckDeleted() ) {
				EntityEntry entry = persistenceContext.getEntry( existing );
				Status status = entry.getStatus();
				if ( status == Status.DELETED || status == Status.GONE ) {
					return null;
				}
			}
			return existing;
		}
		else {
			if ( LOG.isTraceEnabled() ) {
				LOG.trace( "Creating new proxy for entity" );
			}
			return createProxy( event, persister, keyToLoad, persistenceContext );
		}
	}

	private Object createProxy(
			LoadEvent event,
			EntityPersister persister,
			EntityKey keyToLoad,
			PersistenceContext persistenceContext) {
		// return new uninitialized proxy
		Object proxy = persister.createProxy( event.getEntityId(), event.getSession() );
		persistenceContext.getBatchFetchQueue().addBatchLoadableEntityKey( keyToLoad );
		persistenceContext.addProxy( keyToLoad, proxy );
		return proxy;
	}

	/**
	 * If the class to be loaded has been configured with a cache, then lock
	 * given id in that cache and then perform the load.
	 *
	 * @param event The initiating load request event
	 * @param persister The persister corresponding to the entity to be loaded
	 * @param keyToLoad The key of the entity to be loaded
	 * @param options The defined load options
	 * @param source The originating session
	 *
	 * @return The loaded entity
	 */
	private Object lockAndLoad(
			final LoadEvent event,
			final EntityPersister persister,
			final EntityKey keyToLoad,
			final LoadType options,
			final SessionImplementor source) {
		SoftLock lock = null;
		final Object ck;
		final EntityDataAccess cache = persister.getCacheAccessStrategy();
		final boolean canWriteToCache = persister.canWriteToCache();
		if ( canWriteToCache ) {
			ck = cache.generateCacheKey(
					event.getEntityId(),
					persister,
					source.getFactory(),
					source.getTenantIdentifier()
			);
			lock = cache.lockItem( source, ck, null );
		}
		else {
			ck = null;
		}

		Object entity;
		try {
			entity = load( event, persister, keyToLoad, options );
		}
		finally {
			if ( canWriteToCache ) {
				cache.unlockItem( source, ck, lock );
			}
		}

		return source.getPersistenceContextInternal().proxyFor( persister, keyToLoad, entity );
	}


	/**
	 * Coordinates the efforts to load a given entity.  First, an attempt is
	 * made to load the entity from the session-level cache.  If not found there,
	 * an attempt is made to locate it in second-level cache.  Lastly, an
	 * attempt is made to load it directly from the datasource.
	 *
	 * @param event The load event
	 * @param persister The persister for the entity being requested for load
	 * @param keyToLoad The EntityKey representing the entity to be loaded.
	 * @param options The load options.
	 *
	 * @return The loaded entity, or null.
	 */
	private Object doLoad(
			final LoadEvent event,
			final EntityPersister persister,
			final EntityKey keyToLoad,
			final LoadType options) {

		final EventSource session = event.getSession();
		final boolean traceEnabled = LOG.isTraceEnabled();
		if ( traceEnabled ) {
			LOG.tracev(
					"Attempting to resolve: {0}",
					MessageHelper.infoString( persister, event.getEntityId(), session.getFactory() )
			);
		}

		CacheEntityLoaderHelper.PersistenceContextEntry persistenceContextEntry
				= CacheEntityLoaderHelper.INSTANCE.loadFromSessionCache( event, keyToLoad, options );
		Object entity = persistenceContextEntry.getEntity();

		if ( entity != null ) {
			return persistenceContextEntry.isManaged() ? entity : null;
		}

		entity = CacheEntityLoaderHelper.INSTANCE.loadFromSecondLevelCache( event, persister, keyToLoad );
		if ( entity != null ) {
			if ( traceEnabled ) {
				LOG.tracev(
						"Resolved object in second-level cache: {0}",
						MessageHelper.infoString( persister, event.getEntityId(), session.getFactory() )
				);
			}
		}
		else {
			if ( traceEnabled ) {
				LOG.tracev(
						"Object not resolved in any cache: {0}",
						MessageHelper.infoString( persister, event.getEntityId(), session.getFactory() )
				);
			}
			entity = loadFromDatasource( event, persister );
		}

		if ( entity != null && persister.hasNaturalIdentifier() ) {
			final PersistenceContext persistenceContext = session.getPersistenceContextInternal();
			persistenceContext.getNaturalIdResolutions().cacheResolutionFromLoad(
					event.getEntityId(),
					persister.getNaturalIdMapping().extractNaturalIdFromEntity( entity, session ),
					persister
			);
		}

		return entity;
	}

	/**
	 * Performs the process of loading an entity from the configured
	 * underlying datasource.
	 *
	 * @param event The load event
	 * @param persister The persister for the entity being requested for load
	 *
	 * @return The object loaded from the datasource, or null if not found.
	 */
	protected Object loadFromDatasource(
			final LoadEvent event,
			final EntityPersister persister) {
		Object entity = persister.load(
				event.getEntityId(),
				event.getInstanceToLoad(),
				event.getLockOptions(),
				event.getSession(),
				event.getReadOnly()
		);

		// todo (6.0) : this is a change from previous versions
		//		specifically the load call previously always returned a non-proxy
		//		so we emulate that here.  Longer term we should make the
		//		persister/loader/initializer sensitive to this fact - possibly
		//		passing LoadType along

		if ( entity instanceof HibernateProxy ) {
			entity = ( (HibernateProxy) entity ).getHibernateLazyInitializer().getImplementation();
		}

		final StatisticsImplementor statistics = event.getSession().getFactory().getStatistics();
		if ( event.isAssociationFetch() && statistics.isStatisticsEnabled() ) {
			statistics.fetchEntity( event.getEntityClassName() );
		}

		return entity;
	}

}
