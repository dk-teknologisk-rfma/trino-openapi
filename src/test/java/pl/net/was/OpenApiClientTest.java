/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.net.was;

import io.swagger.v3.oas.models.media.Schema;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.predicate.ValueSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static org.assertj.core.api.Assertions.assertThat;

class OpenApiClientTest {
    /**
     * Reproduces the scenario from
     * <a href="https://github.com/nineinchnick/trino-openapi/issues/161">issue
     * #161</a> and
     * <a href="https://github.com/nineinchnick/trino-openapi/pull/157">PR #157</a>:
     * {@code WHERE ts = TIMESTAMP '2025-04-18 12:30:45.123'}, encoded by Trino as
     * epoch micros, must be
     * turned back into a correct RFC 3339 string, not a date 1000x too far in the
     * future (e.g. year 57266).
     */
    @Test
    void shouldEncodeTimestampFilterUsingEpochMicros() {
        OpenApiColumn column = OpenApiColumn.builder()
                .setName("ts")
                .setSourceName("ts")
                .setType(TIMESTAMP_MILLIS)
                .setSourceType(new Schema<>().format("date-time"))
                .build();

        long epochMicros = Instant.parse("2025-04-18T12:30:45.123Z").getEpochSecond() * 1_000_000
                + Instant.parse("2025-04-18T12:30:45.123Z").getNano() / 1_000;

        TupleDomain<ColumnHandle> constraint = TupleDomain.withColumnDomains(Map.of(
                column.getHandle(),
                Domain.create(ValueSet.of(TIMESTAMP_MILLIS, epochMicros), false)));

        Object value = OpenApiClient.getFilter(column, constraint);

        assertThat(value).isEqualTo("2025-04-18T12:30:45.123Z");
    }

    @Test
    void shouldReturnNullWhenNoConstraintPresent() {
        OpenApiColumn column = OpenApiColumn.builder()
                .setName("ts")
                .setSourceName("ts")
                .setType(TIMESTAMP_MILLIS)
                .setSourceType(new Schema<>().format("date-time"))
                .build();

        assertThat(OpenApiClient.getFilter(column, TupleDomain.all())).isNull();
    }

    @Test
    void shouldEncodeTimestampWithoutSourceFormatSet() {
        // OpenApiColumn schemas aren't guaranteed to have the "format" property set
        // explicitly;
        // this previously threw a NullPointerException
        OpenApiColumn column = OpenApiColumn.builder()
                .setName("ts")
                .setSourceName("ts")
                .setType(TIMESTAMP_MILLIS)
                .setSourceType(new Schema<>())
                .build();

        long epochMicros = Instant.parse("2025-04-18T12:30:45Z").getEpochSecond() * 1_000_000;

        TupleDomain<ColumnHandle> constraint = TupleDomain.withColumnDomains(Map.of(
                column.getHandle(),
                Domain.create(ValueSet.of(TIMESTAMP_MILLIS, epochMicros), false)));

        assertThat(OpenApiClient.getFilter(column, constraint)).isEqualTo("2025-04-18T12:30:45Z");
    }
}
