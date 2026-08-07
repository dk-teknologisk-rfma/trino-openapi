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

import com.fasterxml.jackson.databind.node.TextNode;
import io.swagger.v3.oas.models.media.Schema;
import io.trino.spi.TrinoException;
import io.trino.spi.type.DateType;
import io.trino.spi.type.TimestampType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTrinoConverterTest
{
    @Test
    void shouldConvertDifferentDateTimeFormatsIntoLong()
    {
        var type = TimestampType.createTimestampType(3);
        var schema = new Schema<>();

        schema.setFormat("date-time");

        assertThat(JsonTrinoConverter.convert(TextNode.valueOf("2024-09-24T19:17:58.638+04:00"), type, schema))
                .isEqualTo(1727191078638000L);

        assertThat(JsonTrinoConverter.convert(TextNode.valueOf("2024-09-24T18:30:47.94+04:00"), type, schema))
                .isEqualTo(1727188247940000L);
    }

    @Test
    void shouldPreserveMicrosecondPrecisionWhenParsing()
    {
        var type = TimestampType.createTimestampType(6);
        var schema = new Schema<>();
        schema.setFormat("date-time");

        // previously truncated to millis via Instant::toEpochMilli, losing the ".123" microseconds
        assertThat(JsonTrinoConverter.convert(TextNode.valueOf("2024-09-24T19:17:58.638123Z"), type, schema))
                .isEqualTo(1727205478638123L);
    }

    @Test
    void shouldFormatTimestampUsingEpochMicrosNotMillis()
    {
        // this is the exact scenario from https://github.com/nineinchnick/trino-openapi/issues/161 and
        // https://github.com/nineinchnick/trino-openapi/pull/157: a TIMESTAMP predicate value, as encoded by
        // Trino (epoch micros for short timestamps), must not be interpreted as epoch millis, or dates end up
        // 1000x too far in the future (e.g. year 57266 instead of 2025)
        var type = TimestampType.createTimestampType(3);
        var schema = new Schema<>();
        schema.setFormat("date-time");

        long epochMicros = (Long) JsonTrinoConverter.convert(TextNode.valueOf("2025-04-18T12:30:45.123Z"), type, schema);

        assertThat(JsonTrinoConverter.formatTimestamp(epochMicros, type, schema))
                .isEqualTo("2025-04-18T12:30:45.123Z");
    }

    @Test
    void shouldFormatTimestampWithoutFractionWhenWholeSecond()
    {
        var type = TimestampType.createTimestampType(3);
        var schema = new Schema<>();
        schema.setFormat("date-time");

        long epochMicros = (Long) JsonTrinoConverter.convert(TextNode.valueOf("2025-04-18T12:30:45Z"), type, schema);

        assertThat(JsonTrinoConverter.formatTimestamp(epochMicros, type, schema))
                .isEqualTo("2025-04-18T12:30:45Z");
    }

    @Test
    void shouldFormatTimestampWithMicrosecondPrecision()
    {
        var type = TimestampType.createTimestampType(6);
        var schema = new Schema<>();
        schema.setFormat("date-time");

        assertThat(JsonTrinoConverter.formatTimestamp(1727205478638123L, type, schema))
                .isEqualTo("2024-09-24T19:17:58.638123Z");
    }

    @Test
    void shouldFormatTimestampBeforeUnixEpoch()
    {
        var type = TimestampType.createTimestampType(3);
        var schema = new Schema<>();
        schema.setFormat("date-time");

        long epochMicros = (Long) JsonTrinoConverter.convert(TextNode.valueOf("1969-12-31T23:59:59.500Z"), type, schema);

        assertThat(JsonTrinoConverter.formatTimestamp(epochMicros, type, schema))
                .isEqualTo("1969-12-31T23:59:59.5Z");
    }

    @Test
    void shouldRoundTripTimestampsThroughDifferentOffsets()
    {
        var type = TimestampType.createTimestampType(3);
        var schema = new Schema<>();
        schema.setFormat("date-time");

        for (String input : new String[] {
                "2024-09-24T19:17:58.638+04:00",
                "2024-09-24T15:17:58.638Z",
                "2024-09-24T07:17:58.638-08:00",
                "2024-09-24T18:30:47.94+04:00",
        }) {
            long epochMicros = (Long) JsonTrinoConverter.convert(TextNode.valueOf(input), type, schema);
            String formatted = JsonTrinoConverter.formatTimestamp(epochMicros, type, schema);
            // formatting again should reproduce the same instant (formatted always in UTC)
            assertThat(JsonTrinoConverter.convert(TextNode.valueOf(formatted), type, schema))
                    .isEqualTo(epochMicros);
        }
    }

    @Test
    void shouldRejectUnsupportedLongTimestampPrecision()
    {
        var type = TimestampType.createTimestampType(9);
        var schema = new Schema<>();
        schema.setFormat("date-time");

        assertThatThrownBy(() -> JsonTrinoConverter.formatTimestamp(0L, type, schema))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("Unsupported timestamp precision");
    }

    @Test
    void shouldFormatTimestampWhenSchemaFormatIsMissing()
    {
        // sourceType schemas built for columns don't always set the "format" property explicitly (see
        // OpenApiColumn usages in tests), which previously caused a NullPointerException
        var type = TimestampType.createTimestampType(3);
        var schemaWithFormat = new Schema<>().format("date-time");
        var schemaWithoutFormat = new Schema<>();

        long epochMicros = (Long) JsonTrinoConverter.convert(TextNode.valueOf("2025-04-18T12:30:45.123Z"), type, schemaWithFormat);

        assertThat(JsonTrinoConverter.formatTimestamp(epochMicros, type, schemaWithoutFormat))
                .isEqualTo("2025-04-18T12:30:45.123Z");
    }

    @Test
    void shouldConvertDateToLong()
    {
        var type = DateType.DATE;
        var schema = new Schema<>();

        schema.setFormat("date");

        assertThat(JsonTrinoConverter.convert(TextNode.valueOf("2024-09-24"), type, schema))
                .isEqualTo(19990);
    }
}
