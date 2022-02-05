/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.type.descriptor.java;

import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import jakarta.persistence.TemporalType;

import org.hibernate.dialect.Dialect;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcTypeIndicators;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * Java type descriptor for the LocalDateTime type.
 *
 * @author Steve Ebersole
 */
public class OffsetTimeJavaType extends AbstractTemporalJavaType<OffsetTime> {
	/**
	 * Singleton access
	 */
	public static final OffsetTimeJavaType INSTANCE = new OffsetTimeJavaType();

	public OffsetTimeJavaType() {
		super( OffsetTime.class, ImmutableMutabilityPlan.instance() );
	}

	@Override
	public TemporalType getPrecision() {
		return TemporalType.TIME;
	}

	@Override
	public JdbcType getRecommendedJdbcType(JdbcTypeIndicators context) {
		return context.getTypeConfiguration().getJdbcTypeRegistry().getDescriptor( Types.TIME );
	}

	@Override
	protected <X> TemporalJavaType<X> forTimePrecision(TypeConfiguration typeConfiguration) {
		//noinspection unchecked
		return (TemporalJavaType<X>) this;
	}

	@Override
	public String toString(OffsetTime value) {
		return DateTimeFormatter.ISO_OFFSET_TIME.format( value );
	}

	@Override
	public OffsetTime fromString(CharSequence string) {
		return OffsetTime.from( DateTimeFormatter.ISO_OFFSET_TIME.parse( string ) );
	}

	@Override
	@SuppressWarnings("unchecked")
	public <X> X unwrap(OffsetTime offsetTime, Class<X> type, WrapperOptions options) {
		if ( offsetTime == null ) {
			return null;
		}

		if ( OffsetTime.class.isAssignableFrom( type ) ) {
			return (X) offsetTime;
		}

		if ( Time.class.isAssignableFrom( type ) ) {
			return (X) Time.valueOf( offsetTime.toLocalTime() );
		}

		final ZonedDateTime zonedDateTime = offsetTime.atDate( LocalDate.of( 1970, 1, 1 ) ).toZonedDateTime();

		if ( Timestamp.class.isAssignableFrom( type ) ) {
			/*
			 * Workaround for HHH-13266 (JDK-8061577).
			 * Ideally we'd want to use Timestamp.from( offsetDateTime.toInstant() ), but this won't always work.
			 * Timestamp.from() assumes the number of milliseconds since the epoch
			 * means the same thing in Timestamp and Instant, but it doesn't, in particular before 1900.
			 */
			return (X) Timestamp.valueOf( zonedDateTime.toLocalDateTime() );
		}

		if ( Calendar.class.isAssignableFrom( type ) ) {
			return (X) GregorianCalendar.from( zonedDateTime );
		}

		final Instant instant = zonedDateTime.toInstant();

		if ( Long.class.isAssignableFrom( type ) ) {
			return (X) Long.valueOf( instant.toEpochMilli() );
		}

		if ( Date.class.isAssignableFrom( type ) ) {
			return (X) Date.from( instant );
		}

		throw unknownUnwrap( type );
	}

	@Override
	public <X> OffsetTime wrap(X value, WrapperOptions options) {
		if ( value == null ) {
			return null;
		}

		if (value instanceof OffsetTime) {
			return (OffsetTime) value;
		}

		/*
		 * Also, in order to fix HHH-13357, and to be consistent with the conversion to Time (see above),
		 * we set the offset to the current offset of the JVM (OffsetDateTime.now().getOffset()).
		 * This is different from setting the *zone* to the current *zone* of the JVM (ZoneId.systemDefault()),
		 * since a zone has a varying offset over time,
		 * thus the zone might have a different offset for the given timezone than it has for the current date/time.
		 * For example, if the timestamp represents 1970-01-01TXX:YY,
		 * and the JVM is set to use Europe/Paris as a timezone, and the current time is 2019-04-16-08:53,
		 * then applying the JVM timezone to the timestamp would result in the offset +01:00,
		 * but applying the JVM offset would result in the offset +02:00, since DST is in effect at 2019-04-16-08:53.
		 *
		 * Of course none of this would be a problem if we just stored the offset in the database,
		 * but I guess there are historical reasons that explain why we don't.
		 */
		ZoneOffset offset = OffsetDateTime.now().getOffset();

		if (value instanceof Time) {
			return ( (Time) value ).toLocalTime().atOffset( offset );
		}

		if (value instanceof Timestamp) {
			final Timestamp ts = (Timestamp) value;
			/*
			 * Workaround for HHH-13266 (JDK-8061577).
			 * Ideally we'd want to use OffsetDateTime.ofInstant( ts.toInstant(), ... ), but this won't always work.
			 * ts.toInstant() assumes the number of milliseconds since the epoch
			 * means the same thing in Timestamp and Instant, but it doesn't, in particular before 1900.
			 */
			return ts.toLocalDateTime().toLocalTime().atOffset( offset );
		}

		if (value instanceof Date) {
			final Date date = (Date) value;
			return OffsetTime.ofInstant( date.toInstant(), offset );
		}

		if (value instanceof Long) {
			return OffsetTime.ofInstant( Instant.ofEpochMilli( (Long) value ), offset );
		}

		if (value instanceof Calendar) {
			final Calendar calendar = (Calendar) value;
			return OffsetTime.ofInstant( calendar.toInstant(), calendar.getTimeZone().toZoneId() );
		}

		throw unknownWrap( value.getClass() );
	}

	@Override
	public int getDefaultSqlPrecision(Dialect dialect, JdbcType jdbcType) {
		return 0;
	}
}
