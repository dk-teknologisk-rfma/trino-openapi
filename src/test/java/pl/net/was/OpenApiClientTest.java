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
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Int128;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
     *
     * <p>getFilter itself now returns the raw epoch-micros Long (see
     * docs/adr/0002-parameter-rendering-seam.md); the RFC 3339 rendering happens
     * in encodeParam, so this test asserts both.
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
        assertThat(value).isEqualTo(epochMicros);

        assertThat(OpenApiClient.encodeParam(column, ParameterLocation.QUERY, value))
                .containsExactly("2025-04-18T12:30:45.123Z");
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

        Object value = OpenApiClient.getFilter(column, constraint);
        assertThat(OpenApiClient.encodeParam(column, ParameterLocation.QUERY, value))
                .containsExactly("2025-04-18T12:30:45Z");
    }

    @Test
    void shouldEncodeShortDecimalPreservingFullPrecision() {
        // exact decimal rendering, never via double: this used to truncate to 3
        // fraction digits and apply a locale grouping separator via NumberFormat
        OpenApiColumn column = decimalColumn(18, 8);
        String rendered = encodeShortDecimal(column, "55.12345678", 8);
        assertThat(rendered).isEqualTo("55.12345678");
    }

    @Test
    void shouldEncodeShortDecimalWithoutGroupingSeparator() {
        // decimal(18,8) always carries its full scale, so 300000 renders with 8
        // fraction digits - what this guards against is a THOUSANDS separator
        // (e.g. "300,000.00000000"), which is what the old NumberFormat-based
        // rendering would have produced
        OpenApiColumn column = decimalColumn(18, 8);
        String rendered = encodeShortDecimal(column, "300000", 8);
        assertThat(rendered).isEqualTo("300000.00000000");
        assertThat(rendered).doesNotContain(",");
    }

    @Test
    void shouldEncodeShortDecimalUnderNonEnglishLocale() {
        // a locale that uses a comma as the decimal separator and a period as the
        // grouping separator; if the encoder ever regresses to NumberFormat, this
        // is the test that catches it on a non-English CI agent
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("da-DK"));
        try {
            OpenApiColumn column = decimalColumn(18, 8);
            assertThat(encodeShortDecimal(column, "300000", 8)).isEqualTo("300000.00000000");
            assertThat(encodeShortDecimal(column, "55.12345678", 8)).isEqualTo("55.12345678");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void shouldEncodeLongDecimalPreservingExactValue() {
        DecimalType type = DecimalType.createDecimalType(30, 10);
        OpenApiColumn column = OpenApiColumn.builder()
                .setName("value")
                .setSourceName("value")
                .setType(type)
                .setSourceType(new Schema<>().type("number"))
                .build();
        BigInteger unscaled = new BigInteger("123456789012345678901234567890");
        Int128 int128 = Int128.valueOf(unscaled);

        List<String> rendered = OpenApiClient.encodeParam(column, ParameterLocation.QUERY, int128);
        assertThat(rendered).containsExactly("12345678901234567890.1234567890");
    }

    @Test
    void shouldEncodeArrayOfDecimals() {
        // regression test: array(decimal(18,8)) predicates used to throw
        // ClassCastException (LongArrayBlock cannot be cast to VariableWidthBlock)
        // because the old encoder assumed every array was VARCHAR
        DecimalType elementType = DecimalType.createDecimalType(18, 8);
        OpenApiColumn column = arrayColumn(elementType, new Schema<>().type("number"), "form", false);
        Block block = shortDecimalArrayBlock(elementType, "8.0", "55.0", "11.0", "56.0");

        List<String> rendered = OpenApiClient.encodeParam(column, ParameterLocation.QUERY, block);
        assertThat(rendered).containsExactly("8.00000000,55.00000000,11.00000000,56.00000000");
    }

    @Test
    void shouldEncodeArrayOfDoubles() {
        OpenApiColumn column = arrayColumn(DOUBLE, new Schema<>().type("number").format("double"), "form", false);
        BlockBuilder builder = DOUBLE.createBlockBuilder(null, 2);
        DOUBLE.writeDouble(builder, 1.5);
        DOUBLE.writeDouble(builder, 2.25);
        Block block = builder.build();

        List<String> rendered = OpenApiClient.encodeParam(column, ParameterLocation.QUERY, block);
        assertThat(rendered).containsExactly("1.5,2.25");
    }

    @Test
    void shouldEncodeArrayOfBigints() {
        OpenApiColumn column = arrayColumn(BIGINT, new Schema<>().type("integer"), "form", true);
        BlockBuilder builder = BIGINT.createBlockBuilder(null, 3);
        BIGINT.writeLong(builder, 1L);
        BIGINT.writeLong(builder, 2L);
        BIGINT.writeLong(builder, 3L);
        Block block = builder.build();

        // explode=true (query default) -> repeated tokens, not comma-joined
        List<String> rendered = OpenApiClient.encodeParam(column, ParameterLocation.QUERY, block);
        assertThat(rendered).containsExactly("1", "2", "3");
    }

    @Test
    void shouldEncodeArrayOfVarcharsUnchanged() {
        // regression guard: this path worked before the refactor and must keep working
        OpenApiColumn column = arrayColumn(VARCHAR, new Schema<>().type("string"), "form", false);
        BlockBuilder builder = VARCHAR.createBlockBuilder(null, 2);
        VARCHAR.writeString(builder, "a");
        VARCHAR.writeString(builder, "b");
        Block block = builder.build();

        List<String> rendered = OpenApiClient.encodeParam(column, ParameterLocation.QUERY, block);
        assertThat(rendered).containsExactly("a,b");
    }

    @Test
    void shouldRejectMapQueryParameters() {
        io.trino.spi.type.MapType mapType = new io.trino.spi.type.MapType(VARCHAR, VARCHAR, new io.trino.spi.type.TypeOperators());
        OpenApiColumn column = OpenApiColumn.builder()
                .setName("filters")
                .setSourceName("filters")
                .setType(mapType)
                .setSourceType(new Schema<>().type("object"))
                .build();

        assertThatThrownBy(() -> OpenApiClient.encodeParam(column, ParameterLocation.QUERY, "irrelevant"))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("map/row query parameters are not supported for parameter filters");
    }

    @Test
    void shouldRejectRowQueryParameters() {
        RowType rowType = RowType.rowType(RowType.field("a", VARCHAR));
        OpenApiColumn column = OpenApiColumn.builder()
                .setName("filters")
                .setSourceName("filters")
                .setType(rowType)
                .setSourceType(new Schema<>().type("object"))
                .build();

        assertThatThrownBy(() -> OpenApiClient.encodeParam(column, ParameterLocation.QUERY, "irrelevant"))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("map/row query parameters are not supported for parameter filters");
    }

    /**
     * dmi-metobs.json declares {@code bbox} with {@code style: form, explode: false}
     * explicitly, so it must always render as one comma-joined query parameter.
     *
     * <p>Note: as fetched, both DMI specs' {@code .../items} endpoints - the ones
     * carrying {@code bbox} - respond with {@code application/geo+json}, not
     * {@code application/json}, so this connector does not currently expose them
     * as tables at all ({@code hasJsonResponse} only recognises exact
     * {@code application/json}). That is a separate, out-of-scope gap; these
     * tests read the {@code bbox} {@code Parameter} straight out of the spec and
     * build an {@link OpenApiColumn} from it the same way {@code OpenApiSpec}
     * would, so they still exercise the real style/explode declarations without
     * depending on that gap being fixed.
     */
    @Test
    void shouldEncodeDmiMetObsBboxAsSingleCommaJoinedParameter() {
        OpenApiColumn bbox = bboxColumnFromSpec("dmi-metobs.json", "/v2/metObs/collections/station/items");
        assertThat(bbox.getStyle()).isEqualTo("form");
        assertThat(bbox.getExplode()).isEqualTo(false);

        DecimalType elementType = (DecimalType) ((ArrayType) bbox.getType()).getElementType();
        Block block = shortDecimalArrayBlock(elementType, "8.0", "55.0", "11.0", "56.0");

        List<String> rendered = OpenApiClient.encodeParam(bbox, ParameterLocation.QUERY, block);
        assertThat(rendered).hasSize(1);
    }

    /**
     * dmi-climateData.json declares the same {@code bbox} parameter with
     * {@code style: form} but no {@code explode} key at all. OAS defaults
     * {@code explode} to {@code true} for {@code style: form}, so this MUST
     * render as repeated query parameters, unlike dmi-metobs above - even though
     * both are "the same" bbox parameter conceptually. This is spec-faithful and
     * correct connector behaviour: DMI's climateData/oceanObs specs omit
     * {@code explode}, DMI's own metObs spec sets it to {@code false} for the
     * same parameter. The fix belongs in the consuming pipeline (a spec_filter),
     * not here - do not "fix" this by special-casing DMI.
     *
     * <p>The swagger-parser (with {@code resolveFully}) fills in the {@code true}
     * OAS default itself, so {@code getExplode()} is already {@code true} rather
     * than {@code null} by the time this connector sees it; either way the wire
     * behaviour - and the reason it differs from dmi-metobs - is the same.
     */
    @Test
    void shouldEncodeDmiClimateDataBboxAsRepeatedParameters() {
        OpenApiColumn bbox = bboxColumnFromSpec("dmi-climateData.json", "/v2/climateData/collections/station/items");
        assertThat(bbox.getStyle()).isEqualTo("form");
        assertThat(bbox.getExplode()).isTrue();

        DecimalType elementType = (DecimalType) ((ArrayType) bbox.getType()).getElementType();
        Block block = shortDecimalArrayBlock(elementType, "8.0", "55.0", "11.0", "56.0");

        List<String> rendered = OpenApiClient.encodeParam(bbox, ParameterLocation.QUERY, block);
        assertThat(rendered).hasSize(4);
    }

    private static OpenApiColumn bboxColumnFromSpec(String specResource, String path) {
        URL specUrl = requireNonNull(OpenApiClientTest.class.getClassLoader().getResource(specResource));
        io.swagger.v3.parser.core.models.ParseOptions parseOptions = new io.swagger.v3.parser.core.models.ParseOptions();
        parseOptions.setResolveFully(true);
        io.swagger.v3.oas.models.OpenAPI openApi = new io.swagger.v3.parser.OpenAPIV3Parser()
                .readLocation(specUrl.getFile(), null, parseOptions)
                .getOpenAPI();
        io.swagger.v3.oas.models.parameters.Parameter bbox = openApi.getPaths().get(path).getGet()
                .getParameters().stream()
                .filter(p -> "bbox".equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bbox parameter on " + path + " in " + specResource));
        DecimalType elementType = DecimalType.createDecimalType(18, 8);
        return OpenApiColumn.builder()
                .setName("bbox")
                .setSourceName("bbox")
                .setType(new ArrayType(elementType))
                .setSourceType(new Schema<>().items(new Schema<>().type("number")))
                .setStyle(bbox.getStyle() == null ? null : bbox.getStyle().toString())
                .setExplode(bbox.getExplode())
                .build();
    }

    private static OpenApiColumn decimalColumn(int precision, int scale) {
        return OpenApiColumn.builder()
                .setName("value")
                .setSourceName("value")
                .setType(DecimalType.createDecimalType(precision, scale))
                .setSourceType(new Schema<>().type("number"))
                .build();
    }

    private static OpenApiColumn arrayColumn(Type elementType, Schema<?> itemSchema, String style, boolean explode) {
        return OpenApiColumn.builder()
                .setName("values")
                .setSourceName("values")
                .setType(new ArrayType(elementType))
                .setSourceType(new Schema<>().items(itemSchema))
                .setStyle(style)
                .setExplode(explode)
                .build();
    }

    private static String encodeShortDecimal(OpenApiColumn column, String decimal, int scale) {
        long unscaled = new java.math.BigDecimal(decimal).unscaledValue()
                .multiply(BigInteger.TEN.pow(scale - new java.math.BigDecimal(decimal).scale()))
                .longValueExact();
        return OpenApiClient.encodeParam(column, ParameterLocation.QUERY, unscaled).get(0);
    }

    private static Block shortDecimalArrayBlock(DecimalType type, String... values) {
        BlockBuilder builder = type.createBlockBuilder(null, values.length);
        for (String value : values) {
            java.math.BigDecimal decimal = new java.math.BigDecimal(value).setScale(type.getScale());
            type.writeLong(builder, decimal.unscaledValue().longValueExact());
        }
        return builder.build();
    }

}
