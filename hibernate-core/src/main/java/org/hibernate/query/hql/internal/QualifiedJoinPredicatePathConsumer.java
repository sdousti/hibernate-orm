/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.hql.internal;

import java.util.Locale;

import org.hibernate.query.SemanticException;
import org.hibernate.query.hql.spi.SemanticPathPart;
import org.hibernate.query.hql.spi.SqmCreationProcessingState;
import org.hibernate.query.hql.spi.SqmCreationState;
import org.hibernate.query.sqm.tree.SqmQuery;
import org.hibernate.query.sqm.tree.domain.SqmCorrelation;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmFromClause;
import org.hibernate.query.sqm.tree.from.SqmQualifiedJoin;
import org.hibernate.query.sqm.tree.from.SqmRoot;
import org.hibernate.query.sqm.tree.select.SqmQuerySpec;
import org.hibernate.query.sqm.tree.select.SqmSelectQuery;
import org.hibernate.query.sqm.tree.select.SqmSubQuery;

/**
 * Specialized consumer for processing domain model paths occurring as part
 * of a join predicate
 *
 * @author Steve Ebersole
 */
public class QualifiedJoinPredicatePathConsumer extends BasicDotIdentifierConsumer {
	private final SqmQualifiedJoin<?, ?> sqmJoin;

	public QualifiedJoinPredicatePathConsumer(
			SqmQualifiedJoin<?, ?> sqmJoin,
			SqmCreationState creationState) {
		super( creationState );
		this.sqmJoin = sqmJoin;
	}

	@Override
	protected SemanticPathPart createBasePart() {
		return new BaseLocalSequencePart() {
			@Override
			protected void validateAsRoot(SqmFrom<?, ?> pathRoot) {
				final SqmRoot<?> root = pathRoot.findRoot();
				final SqmRoot<?> joinRoot = sqmJoin.findRoot();
				if ( root != joinRoot ) {
					// The root of a path within a join condition doesn't have the same root as the current join we are processing.
					// The aim of this check is to prevent uses of different "spaces" i.e. `from A a, B b join b.id = a.id` would be illegal
					SqmCreationProcessingState processingState = getCreationState().getCurrentProcessingState();
					// First, we need to find out if the current join is part of current processing query
					final SqmQuery<?> currentProcessingQuery = processingState.getProcessingQuery();
					if ( currentProcessingQuery instanceof SqmSelectQuery<?> ) {
						final SqmQuerySpec<?> querySpec = ( (SqmSelectQuery<?>) currentProcessingQuery ).getQuerySpec();
						final SqmFromClause fromClause = querySpec.getFromClause();
						// If the current processing query contains the root of the current join,
						// then the root of the processing path must be a root of one of the parent queries
						if ( fromClause != null && fromClause.getRoots().contains( joinRoot ) ) {
							// It is allowed to use correlations from the same query
							if ( !( root instanceof SqmCorrelation<?, ?> ) || !fromClause.getRoots().contains( root ) ) {
								validateAsRootOnParentQueryClosure(
										pathRoot,
										root,
										processingState.getParentProcessingState()
								);
							}
							return;
						}
					}
					// If the current join is not part of the processing query, this must be a subquery in the ON clause
					// in which case the path root is allowed to occur in the current processing query as root
					if ( currentProcessingQuery instanceof SqmSubQuery<?> ) {
						validateAsRootOnParentQueryClosure( pathRoot, root, processingState );
						return;
					}
					throw new SemanticException(
							String.format(
									Locale.ROOT,
									"SqmQualifiedJoin predicate referred to SqmRoot [`%s`] other than the join's root [`%s`]",
									pathRoot.getNavigablePath(),
									sqmJoin.getNavigablePath()
							)
					);
				}

				super.validateAsRoot( pathRoot );
			}

			private void validateAsRootOnParentQueryClosure(
					SqmFrom<?, ?> pathRoot,
					SqmRoot<?> root,
					SqmCreationProcessingState processingState) {
				while ( processingState != null ) {
					final SqmQuery<?> processingQuery = processingState.getProcessingQuery();
					if ( processingQuery instanceof SqmSelectQuery<?> ) {
						final SqmQuerySpec<?> querySpec = ( (SqmSelectQuery<?>) processingQuery ).getQuerySpec();
						final SqmFromClause fromClause = querySpec.getFromClause();
						// If we are in a subquery, the "foreign" from element could be one of the subquery roots,
						// which is totally fine. The aim of this check is to prevent uses of different "spaces"
						// i.e. `from A a, B b join b.id = a.id` would be illegal
						if ( fromClause != null && fromClause.getRoots().contains( root ) ) {
							super.validateAsRoot( pathRoot );
							return;
						}
					}
					processingState = processingState.getParentProcessingState();
				}
				throw new SemanticException(
						String.format(
								Locale.ROOT,
								"SqmQualifiedJoin predicate referred to SqmRoot [`%s`] other than the join's root [`%s`]",
								pathRoot.getNavigablePath(),
								sqmJoin.getNavigablePath()
						)
				);
			}
		};
	}
}
