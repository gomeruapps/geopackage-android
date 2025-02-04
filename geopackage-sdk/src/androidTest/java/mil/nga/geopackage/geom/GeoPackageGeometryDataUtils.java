package mil.nga.geopackage.geom;

import junit.framework.TestCase;

import java.io.IOException;
import java.nio.ByteOrder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import mil.nga.geopackage.BoundingBox;
import mil.nga.geopackage.GeoPackage;
import mil.nga.geopackage.GeoPackageException;
import mil.nga.geopackage.TestUtils;
import mil.nga.geopackage.db.TableColumnKey;
import mil.nga.geopackage.features.columns.GeometryColumns;
import mil.nga.geopackage.features.columns.GeometryColumnsDao;
import mil.nga.geopackage.features.user.FeatureCursor;
import mil.nga.geopackage.features.user.FeatureDao;
import mil.nga.geopackage.features.user.FeatureRow;
import mil.nga.geopackage.features.user.FeatureTable;
import mil.nga.geopackage.features.user.FeatureTableMetadata;
import mil.nga.geopackage.srs.SpatialReferenceSystem;
import mil.nga.geopackage.srs.SpatialReferenceSystemDao;
import mil.nga.proj.Projection;
import mil.nga.proj.ProjectionConstants;
import mil.nga.sf.CircularString;
import mil.nga.sf.CompoundCurve;
import mil.nga.sf.CurvePolygon;
import mil.nga.sf.Geometry;
import mil.nga.sf.GeometryCollection;
import mil.nga.sf.GeometryEnvelope;
import mil.nga.sf.GeometryType;
import mil.nga.sf.LineString;
import mil.nga.sf.MultiLineString;
import mil.nga.sf.MultiPoint;
import mil.nga.sf.MultiPolygon;
import mil.nga.sf.Point;
import mil.nga.sf.Polygon;
import mil.nga.sf.PolyhedralSurface;
import mil.nga.sf.TIN;
import mil.nga.sf.Triangle;
import mil.nga.sf.proj.GeometryTransform;
import mil.nga.sf.wkb.GeometryCodes;
import mil.nga.sf.wkb.GeometryReader;
import mil.nga.sf.wkb.GeometryWriter;

/**
 * GeoPackage Geometry Data test utils
 *
 * @author osbornb
 */
public class GeoPackageGeometryDataUtils {

    private static final String TABLE_NAME = "features";
    private static final String COLUMN_NAME = "geom";

    /**
     * Test reading and writing (and comparing) geometry bytes
     *
     * @param geoPackage
     * @throws SQLException
     * @throws IOException
     */
    public static void testReadWriteBytes(GeoPackage geoPackage)
            throws SQLException, IOException {

        GeometryColumnsDao geometryColumnsDao = geoPackage
                .getGeometryColumnsDao();

        if (geometryColumnsDao.isTableExists()) {
            List<GeometryColumns> results = geometryColumnsDao.queryForAll();

            for (GeometryColumns geometryColumns : results) {

                FeatureDao dao = geoPackage.getFeatureDao(geometryColumns);
                TestCase.assertNotNull(dao);

                FeatureCursor cursor = dao.queryForAll();

                while (cursor.moveToNext()) {

                    GeoPackageGeometryData geometryData = cursor.getGeometry();
                    if (geometryData != null) {

                        byte[] geometryDataToBytes = geometryData.toBytes();
                        compareByteArrays(geometryDataToBytes,
                                geometryData.getBytes());

                        GeoPackageGeometryData geometryDataAfterToBytes = geometryData;

                        // Re-retrieve the original geometry data
                        geometryData = cursor.getGeometry();

                        // Compare the original with the toBytes geometry data
                        compareGeometryData(geometryData,
                                geometryDataAfterToBytes);

                        // Create a new geometry data from the bytes and compare
                        // with original
                        GeoPackageGeometryData geometryDataFromBytes = GeoPackageGeometryData
                                .create(geometryDataToBytes);
                        compareGeometryData(geometryData,
                                geometryDataFromBytes);

                        // Set the geometry empty flag and verify the geometry
                        // was not written / read
                        geometryDataAfterToBytes = cursor.getGeometry();
                        geometryDataAfterToBytes.setEmpty(true);
                        geometryDataToBytes = geometryDataAfterToBytes
                                .toBytes();
                        geometryDataFromBytes = GeoPackageGeometryData
                                .create(geometryDataToBytes);
                        TestCase.assertNull(
                                geometryDataFromBytes.getGeometry());
                        compareByteArrays(
                                geometryDataAfterToBytes.getHeaderBytes(),
                                geometryDataFromBytes.getHeaderBytes());

                        // Flip the byte order and verify the header and bytes
                        // no longer matches the original, but the geometries still do
                        geometryDataAfterToBytes = cursor.getGeometry();
                        geometryDataAfterToBytes
                                .setByteOrder(geometryDataAfterToBytes
                                        .getByteOrder() == ByteOrder.BIG_ENDIAN ? ByteOrder.LITTLE_ENDIAN
                                        : ByteOrder.BIG_ENDIAN);
                        geometryDataToBytes = geometryDataAfterToBytes
                                .toBytes();
                        geometryDataFromBytes = GeoPackageGeometryData
                                .create(geometryDataToBytes);
                        compareGeometryData(geometryDataAfterToBytes,
                                geometryDataFromBytes);
                        TestCase.assertFalse(equalByteArrays(
                                geometryDataAfterToBytes.getHeaderBytes(),
                                geometryData.getHeaderBytes()));
                        TestCase.assertFalse(equalByteArrays(
                                geometryDataAfterToBytes.getWkb(),
                                geometryData.getWkb()));
                        TestCase.assertFalse(equalByteArrays(
                                geometryDataAfterToBytes.getBytes(),
                                geometryData.getBytes()));
                        compareGeometries(geometryData.getGeometry(),
                                geometryDataAfterToBytes.getGeometry());
                    }

                }
                cursor.close();
            }
        }

    }

    /**
     * Test transforming geometries between projections
     *
     * @param geoPackage
     * @throws SQLException
     * @throws IOException
     */
    public static void testGeometryProjectionTransform(GeoPackage geoPackage)
            throws SQLException, IOException {

        GeometryColumnsDao geometryColumnsDao = geoPackage
                .getGeometryColumnsDao();

        if (geometryColumnsDao.isTableExists()) {
            List<GeometryColumns> results = geometryColumnsDao.queryForAll();

            for (GeometryColumns geometryColumns : results) {

                FeatureDao dao = geoPackage.getFeatureDao(geometryColumns);
                TestCase.assertNotNull(dao);

                FeatureCursor cursor = dao.queryForAll();

                while (cursor.moveToNext()) {

                    GeoPackageGeometryData geometryData = cursor.getGeometry();
                    if (geometryData != null) {

                        Geometry geometry = geometryData.getGeometry();

                        if (geometry != null) {

                            SpatialReferenceSystemDao srsDao = geoPackage
                                    .getSpatialReferenceSystemDao();
                            long srsId = geometryData.getSrsId();
                            SpatialReferenceSystem srs = srsDao
                                    .queryForId(srsId);

                            long epsg = srs.getOrganizationCoordsysId();
                            Projection projection = srs.getProjection();
                            long toEpsg = -1;
                            if (epsg == ProjectionConstants.EPSG_WORLD_GEODETIC_SYSTEM) {
                                toEpsg = ProjectionConstants.EPSG_WEB_MERCATOR;
                            } else {
                                toEpsg = ProjectionConstants.EPSG_WORLD_GEODETIC_SYSTEM;
                            }
                            GeometryTransform transformTo = GeometryTransform
                                    .create(projection, toEpsg);
                            GeometryTransform transformFrom = srs
                                    .getTransformation(
                                            transformTo.getToProjection());

                            byte[] bytes = geometryData.getWkb();

                            Geometry projectedGeometry = transformTo
                                    .transform(geometry);
                            byte[] projectedBytes = GeoPackageGeometryData
                                    .wkb(projectedGeometry);

                            if (epsg > 0) {
                                TestCase.assertFalse(equalByteArrays(bytes,
                                        projectedBytes));
                            }

                            Geometry restoredGeometry = transformFrom
                                    .transform(projectedGeometry);

                            compareGeometries(geometry, restoredGeometry, .001);
                        }
                    }

                }
                cursor.close();
            }
        }
    }

    /**
     * Compare two geometry datas and verify they are equal
     *
     * @param expected expected geometry data
     * @param actual   actual geometry data
     * @throws IOException upon error
     */
    public static void compareGeometryData(GeoPackageGeometryData expected,
                                           GeoPackageGeometryData actual) throws IOException {

        // Compare geometry data attributes
        TestCase.assertEquals(expected.isExtended(), actual.isExtended());
        TestCase.assertEquals(expected.isEmpty(), actual.isEmpty());
        TestCase.assertEquals(expected.getByteOrder(), actual.getByteOrder());
        TestCase.assertEquals(expected.getSrsId(), actual.getSrsId());
        compareEnvelopes(expected.getEnvelope(), actual.getEnvelope());
        TestCase.assertEquals(expected.getWkbGeometryIndex(),
                actual.getWkbGeometryIndex());

        // Compare header bytes
        compareByteArrays(expected.getHeaderBytes(), actual.getHeaderBytes());

        // Compare geometries
        compareGeometries(expected.getGeometry(), actual.getGeometry());

        // Compare well-known binary geometries
        compareByteArrays(expected.getWkb(), actual.getWkb());

        // Compare all bytes
        compareByteArrays(expected.getBytes(), actual.getBytes());

    }

    /**
     * Compare two geometry envelopes and verify they are equal
     *
     * @param expected
     * @param actual
     */
    private static void compareEnvelopes(GeometryEnvelope expected,
                                         GeometryEnvelope actual) {

        if (expected == null) {
            TestCase.assertNull(actual);
        } else {
            TestCase.assertNotNull(actual);

            TestCase.assertEquals(GeoPackageGeometryData.getIndicator(expected),
                    GeoPackageGeometryData.getIndicator(actual));
            TestCase.assertEquals(expected.getMinX(), actual.getMinX());
            TestCase.assertEquals(expected.getMaxX(), actual.getMaxX());
            TestCase.assertEquals(expected.getMinY(), actual.getMinY());
            TestCase.assertEquals(expected.getMaxY(), actual.getMaxY());
            TestCase.assertEquals(expected.hasZ(), actual.hasZ());
            TestCase.assertEquals(expected.getMinZ(), actual.getMinZ());
            TestCase.assertEquals(expected.getMaxZ(), actual.getMaxZ());
            TestCase.assertEquals(expected.hasM(), actual.hasM());
            TestCase.assertEquals(expected.getMinM(), actual.getMinM());
            TestCase.assertEquals(expected.getMaxM(), actual.getMaxM());
        }

    }

    /**
     * Compare two geometries and verify they are equal
     *
     * @param expected
     * @param actual
     */
    public static void compareGeometries(Geometry expected, Geometry actual) {
        compareGeometries(expected, actual, 0.0);
    }

    /**
     * Compare two geometries and verify they are equal
     *
     * @param expected
     * @param actual
     * @param delta
     */
    public static void compareGeometries(Geometry expected, Geometry actual,
                                         double delta) {
        if (expected == null) {
            TestCase.assertNull(actual);
        } else {
            TestCase.assertNotNull(actual);

            GeometryType geometryType = expected.getGeometryType();
            switch (geometryType) {

                case GEOMETRY:
                    TestCase.fail("Unexpected Geometry Type of "
                            + geometryType.name() + " which is abstract");
                case POINT:
                    comparePoint((Point) expected, (Point) actual, delta);
                    break;
                case LINESTRING:
                    compareLineString((LineString) expected, (LineString) actual,
                            delta);
                    break;
                case POLYGON:
                    comparePolygon((Polygon) expected, (Polygon) actual, delta);
                    break;
                case MULTIPOINT:
                    compareMultiPoint((MultiPoint) expected, (MultiPoint) actual,
                            delta);
                    break;
                case MULTILINESTRING:
                    compareMultiLineString((MultiLineString) expected,
                            (MultiLineString) actual, delta);
                    break;
                case MULTIPOLYGON:
                    compareMultiPolygon((MultiPolygon) expected,
                            (MultiPolygon) actual, delta);
                    break;
                case GEOMETRYCOLLECTION:
                    compareGeometryCollection((GeometryCollection<?>) expected,
                            (GeometryCollection<?>) actual, delta);
                    break;
                case CIRCULARSTRING:
                    compareCircularString((CircularString) expected,
                            (CircularString) actual, delta);
                    break;
                case COMPOUNDCURVE:
                    compareCompoundCurve((CompoundCurve) expected,
                            (CompoundCurve) actual, delta);
                    break;
                case CURVEPOLYGON:
                    compareCurvePolygon((CurvePolygon<?>) expected,
                            (CurvePolygon<?>) actual, delta);
                    break;
                case MULTICURVE:
                    TestCase.fail("Unexpected Geometry Type of "
                            + geometryType.name() + " which is abstract");
                case MULTISURFACE:
                    TestCase.fail("Unexpected Geometry Type of "
                            + geometryType.name() + " which is abstract");
                case CURVE:
                    TestCase.fail("Unexpected Geometry Type of "
                            + geometryType.name() + " which is abstract");
                case SURFACE:
                    TestCase.fail("Unexpected Geometry Type of "
                            + geometryType.name() + " which is abstract");
                case POLYHEDRALSURFACE:
                    comparePolyhedralSurface((PolyhedralSurface) expected,
                            (PolyhedralSurface) actual, delta);
                    break;
                case TIN:
                    compareTIN((TIN) expected, (TIN) actual, delta);
                    break;
                case TRIANGLE:
                    compareTriangle((Triangle) expected, (Triangle) actual, delta);
                    break;
                default:
                    throw new GeoPackageException("Geometry Type not supported: "
                            + geometryType);
            }
        }
    }

    /**
     * Compare to the base attribiutes of two geometries
     *
     * @param expected
     * @param actual
     */
    private static void compareBaseGeometryAttributes(Geometry expected,
                                                      Geometry actual) {
        TestCase.assertEquals(expected.getGeometryType(),
                actual.getGeometryType());
        TestCase.assertEquals(expected.hasZ(), actual.hasZ());
        TestCase.assertEquals(expected.hasM(), actual.hasM());
        TestCase.assertEquals(GeometryCodes.getCode(expected), GeometryCodes.getCode(actual));
    }

    /**
     * Compare the two points for equality
     *
     * @param expected
     * @param actual
     */
    private static void comparePoint(Point expected, Point actual, double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.getX(), actual.getX(), delta);
        TestCase.assertEquals(expected.getY(), actual.getY(), delta);
        if (expected.getZ() == null) {
            TestCase.assertEquals(expected.getZ(), actual.getZ());
        } else {
            TestCase.assertEquals(expected.getZ(), actual.getZ(), delta);
        }
        if (expected.getM() == null) {
            TestCase.assertEquals(expected.getM(), actual.getM());
        } else {
            TestCase.assertEquals(expected.getM(), actual.getM(), delta);
        }
    }

    /**
     * Compare the two line strings for equality
     *
     * @param expected
     * @param actual
     * @parma delta
     */
    private static void compareLineString(LineString expected,
                                          LineString actual, double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numPoints(), actual.numPoints());
        for (int i = 0; i < expected.numPoints(); i++) {
            comparePoint(expected.getPoints().get(i),
                    actual.getPoints().get(i), delta);
        }
    }

    /**
     * Compare the two polygons for equality
     *
     * @param expected
     * @param actual
     * @param delta
     */
    private static void comparePolygon(Polygon expected, Polygon actual,
                                       double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numRings(), actual.numRings());
        for (int i = 0; i < expected.numRings(); i++) {
            compareLineString(expected.getRings().get(i), actual.getRings()
                    .get(i), delta);
        }
    }

    /**
     * Compare the two multi points for equality
     *
     * @param expected
     * @param actual
     * @param delta
     */
    private static void compareMultiPoint(MultiPoint expected,
                                          MultiPoint actual, double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numPoints(), actual.numPoints());
        for (int i = 0; i < expected.numPoints(); i++) {
            comparePoint(expected.getPoints().get(i),
                    actual.getPoints().get(i), delta);
        }
    }

    /**
     * Compare the two multi line strings for equality
     *
     * @param expected
     * @param actual
     * @parma delta
     */
    private static void compareMultiLineString(MultiLineString expected,
                                               MultiLineString actual, double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numLineStrings(),
                actual.numLineStrings());
        for (int i = 0; i < expected.numLineStrings(); i++) {
            compareLineString(expected.getLineStrings().get(i), actual
                    .getLineStrings().get(i), delta);
        }
    }

    /**
     * Compare the two multi polygons for equality
     *
     * @param expected
     * @param actual
     * @param delta
     */
    private static void compareMultiPolygon(MultiPolygon expected,
                                            MultiPolygon actual, double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numPolygons(), actual.numPolygons());
        for (int i = 0; i < expected.numPolygons(); i++) {
            comparePolygon(expected.getPolygons().get(i), actual.getPolygons()
                    .get(i), delta);
        }
    }

    /**
     * Compare the two geometry collections for equality
     *
     * @param expected
     * @param actual
     * @param delta
     */
    private static void compareGeometryCollection(
            GeometryCollection<?> expected, GeometryCollection<?> actual,
            double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numGeometries(), actual.numGeometries());
        for (int i = 0; i < expected.numGeometries(); i++) {
            compareGeometries(expected.getGeometries().get(i), actual
                    .getGeometries().get(i), delta);
        }
    }

    /**
     * Compare the two circular strings for equality
     *
     * @param expected
     * @param actual
     * @parma delta
     */
    private static void compareCircularString(CircularString expected,
                                              CircularString actual, double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numPoints(), actual.numPoints());
        for (int i = 0; i < expected.numPoints(); i++) {
            comparePoint(expected.getPoints().get(i),
                    actual.getPoints().get(i), delta);
        }
    }

    /**
     * Compare the two compound curves for equality
     *
     * @param expected
     * @param actual
     * @parma delta
     */
    private static void compareCompoundCurve(CompoundCurve expected,
                                             CompoundCurve actual, double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numLineStrings(),
                actual.numLineStrings());
        for (int i = 0; i < expected.numLineStrings(); i++) {
            compareLineString(expected.getLineStrings().get(i), actual
                    .getLineStrings().get(i), delta);
        }
    }

    /**
     * Compare the two curve polygons for equality
     *
     * @param expected
     * @param actual
     * @param delta
     */
    private static void compareCurvePolygon(CurvePolygon<?> expected,
                                            CurvePolygon<?> actual, double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numRings(), actual.numRings());
        for (int i = 0; i < expected.numRings(); i++) {
            compareGeometries(expected.getRings().get(i), actual.getRings()
                    .get(i), delta);
        }
    }

    /**
     * Compare the two polyhedral surfaces for equality
     *
     * @param expected
     * @param actual
     * @param delta
     */
    private static void comparePolyhedralSurface(PolyhedralSurface expected,
                                                 PolyhedralSurface actual, double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numPolygons(), actual.numPolygons());
        for (int i = 0; i < expected.numPolygons(); i++) {
            compareGeometries(expected.getPolygons().get(i), actual
                    .getPolygons().get(i), delta);
        }
    }

    /**
     * Compare the two TINs for equality
     *
     * @param expected
     * @param actual
     * @param delta
     */
    private static void compareTIN(TIN expected, TIN actual, double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numPolygons(), actual.numPolygons());
        for (int i = 0; i < expected.numPolygons(); i++) {
            compareGeometries(expected.getPolygons().get(i), actual
                    .getPolygons().get(i), delta);
        }
    }

    /**
     * Compare the two triangles for equality
     *
     * @param expected
     * @param actual
     * @param delta
     */
    private static void compareTriangle(Triangle expected, Triangle actual,
                                        double delta) {

        compareBaseGeometryAttributes(expected, actual);
        TestCase.assertEquals(expected.numRings(), actual.numRings());
        for (int i = 0; i < expected.numRings(); i++) {
            compareLineString(expected.getRings().get(i), actual.getRings()
                    .get(i), delta);
        }
    }

    /**
     * Compare two byte arrays and verify they are equal
     *
     * @param expected
     * @param actual
     */
    public static void compareByteArrays(byte[] expected, byte[] actual) {

        TestCase.assertEquals(expected.length, actual.length);

        for (int i = 0; i < expected.length; i++) {
            TestCase.assertEquals("Byte: " + i, expected[i], actual[i]);
        }

    }

    /**
     * Compare two byte arrays and verify they are equal
     *
     * @param expected
     * @param actual
     * @return true if equal
     */
    public static boolean equalByteArrays(byte[] expected, byte[] actual) {

        boolean equal = expected.length == actual.length;

        for (int i = 0; equal && i < expected.length; i++) {
            equal = expected[i] == actual[i];
        }

        return equal;
    }

    /**
     * Test inserting raw geometry bytes
     *
     * @param geoPackage GeoPackage
     * @throws SQLException upon error
     * @throws IOException  upon error
     */
    public static void testInsertGeometryBytes(GeoPackage geoPackage)
            throws SQLException, IOException {

        final int geometryCount = 100;
        final int commitChunk = 10;

        List<byte[]> geometries = new ArrayList<>();

        for (int i = 0; i < geometryCount; i++) {
            geometries.add(GeometryWriter
                    .writeGeometry(TestUtils.createPoint(false, false)));
        }

        FeatureDao dao = createFeatureTable(geoPackage);

        dao.beginTransaction();

        try {

            for (int count = 0; count < geometries.size(); count++) {

                byte[] geometry = geometries.get(count);

                FeatureRow row = dao.newRow();

                GeoPackageGeometryData geometryData = new GeoPackageGeometryData();
                geometryData.setGeometryBytes(geometry);

                row.setGeometry(geometryData);

                dao.insert(row);

                if (count % commitChunk == 0) {
                    dao.commit();
                }
            }

            dao.endTransaction();
        } catch (Exception e) {
            dao.failTransaction();
            throw e;
        }

        TestCase.assertEquals(geometryCount, dao.count());

        int count = 0;

        FeatureCursor features = dao.query();
        try {
            for (FeatureRow row : features) {
                GeoPackageGeometryData geometryData = row.getGeometry();
                byte[] geometryBytes = geometries.get(count++);
                GeoPackageGeometryDataUtils.compareByteArrays(geometryBytes,
                        geometryData.getWkb());
                TestCase.assertEquals(
                        GeometryReader.readGeometry(geometryBytes),
                        geometryData.getGeometry());
            }
        } finally {
            features.close();
        }

    }

    /**
     * Test inserting raw header bytes
     *
     * @param geoPackage GeoPackage
     * @throws SQLException upon error
     * @throws IOException  upon error
     */
    public static void testInsertHeaderBytes(GeoPackage geoPackage)
            throws SQLException, IOException {

        final int geometryCount = 100;
        final int commitChunk = 7;

        List<Geometry> geometries = new ArrayList<>();

        for (int i = 0; i < geometryCount; i++) {
            geometries.add(TestUtils.createLineString(false, false, false));
        }

        GeoPackageGeometryData geomData = new GeoPackageGeometryData(1234);
        geomData.setByteOrder(ByteOrder.BIG_ENDIAN);
        geomData.setEmpty(false);
        geomData.setExtended(false);

        byte[] header = geomData.getHeaderBytes();

        FeatureDao dao = createFeatureTable(geoPackage);

        dao.beginTransaction();

        try {

            for (int count = 0; count < geometries.size(); count++) {

                Geometry geometry = geometries.get(count);

                FeatureRow row = dao.newRow();

                GeoPackageGeometryData geometryData = new GeoPackageGeometryData(
                        geometry);
                geometryData.setHeaderBytes(header);

                row.setGeometry(geometryData);

                dao.insert(row);

                if (count % commitChunk == 0) {
                    dao.commit();
                }
            }

            dao.endTransaction();
        } catch (Exception e) {
            dao.failTransaction();
            throw e;
        }

        TestCase.assertEquals(geometryCount, dao.count());

        int count = 0;

        FeatureCursor features = dao.query();
        try {
            for (FeatureRow row : features) {
                GeoPackageGeometryData geometryData = row.getGeometry();
                GeoPackageGeometryDataUtils.compareByteArrays(header,
                        geometryData.getHeaderBytes());
                Geometry geometry = geometries.get(count++);
                GeoPackageGeometryDataUtils.compareByteArrays(
                        GeometryWriter.writeGeometry(geometry),
                        geometryData.getWkb());
                TestCase.assertEquals(geometry, geometryData.getGeometry());
            }
        } finally {
            features.close();
        }

    }

    /**
     * Test inserting raw header and geometry bytes
     *
     * @param geoPackage GeoPackage
     * @throws SQLException upon error
     * @throws IOException  upon error
     */
    public static void testInsertHeaderAndGeometryBytes(GeoPackage geoPackage)
            throws SQLException, IOException {

        final int geometryCount = 100;
        final int commitChunk = 13;

        List<byte[]> geometries = new ArrayList<>();

        for (int i = 0; i < geometryCount; i++) {
            geometries.add(GeometryWriter
                    .writeGeometry(TestUtils.createPolygon(false, false)));
        }

        GeoPackageGeometryData geomData = new GeoPackageGeometryData(1234);
        geomData.setByteOrder(ByteOrder.BIG_ENDIAN);
        geomData.setEmpty(false);
        geomData.setExtended(false);

        byte[] header = geomData.getHeaderBytes();

        FeatureDao dao = createFeatureTable(geoPackage);

        dao.beginTransaction();

        try {

            for (int count = 0; count < geometries.size(); count++) {

                byte[] geometry = geometries.get(count);

                FeatureRow row = dao.newRow();

                GeoPackageGeometryData geometryData = new GeoPackageGeometryData();
                geometryData.setHeaderBytes(header);
                geometryData.setGeometryBytes(geometry);

                row.setGeometry(geometryData);

                dao.insert(row);

                if (count % commitChunk == 0) {
                    dao.commit();
                }
            }

            dao.endTransaction();
        } catch (Exception e) {
            dao.failTransaction();
            throw e;
        }

        TestCase.assertEquals(geometryCount, dao.count());

        int count = 0;

        FeatureCursor features = dao.query();
        try {
            for (FeatureRow row : features) {
                GeoPackageGeometryData geometryData = row.getGeometry();
                GeoPackageGeometryDataUtils.compareByteArrays(header,
                        geometryData.getHeaderBytes());
                byte[] geometryBytes = geometries.get(count++);
                GeoPackageGeometryDataUtils.compareByteArrays(geometryBytes,
                        geometryData.getWkb());
                TestCase.assertEquals(
                        GeometryReader.readGeometry(geometryBytes),
                        geometryData.getGeometry());
            }
        } finally {
            features.close();
        }

    }

    /**
     * Test inserting raw bytes
     *
     * @param geoPackage GeoPackage
     * @throws SQLException upon error
     * @throws IOException  upon error
     */
    public static void testInsertBytes(GeoPackage geoPackage)
            throws SQLException, IOException {

        final int geometryCount = 100;
        final int commitChunk = 15;

        List<byte[]> geometries = new ArrayList<>();

        for (int i = 0; i < geometryCount; i++) {
            geometries.add(new GeoPackageGeometryData(
                    TestUtils.createPolygon(false, false)).toBytes());
        }

        FeatureDao dao = createFeatureTable(geoPackage);

        dao.beginTransaction();

        try {

            for (int count = 0; count < geometries.size(); count++) {

                byte[] geometry = geometries.get(count);

                FeatureRow row = dao.newRow();

                GeoPackageGeometryData geometryData = new GeoPackageGeometryData();
                geometryData.setBytes(geometry);

                row.setGeometry(geometryData);

                dao.insert(row);

                if (count % commitChunk == 0) {
                    dao.commit();
                }
            }

            dao.endTransaction();
        } catch (Exception e) {
            dao.failTransaction();
            throw e;
        }

        TestCase.assertEquals(geometryCount, dao.count());

        int count = 0;

        FeatureCursor features = dao.query();
        try {
            for (FeatureRow row : features) {
                GeoPackageGeometryData geometryData = row.getGeometry();
                byte[] geometryDataBytes = geometries.get(count++);
                GeoPackageGeometryDataUtils.compareByteArrays(geometryDataBytes,
                        geometryData.getBytes());
                TestCase.assertEquals(GeoPackageGeometryData
                                .create(geometryDataBytes).getGeometry(),
                        geometryData.getGeometry());
            }
        } finally {
            features.close();
        }

    }

    /**
     * Create a feature table
     *
     * @param geoPackage GeoPackage
     * @return feature dao
     * @throws SQLException upon error
     */
    private static FeatureDao createFeatureTable(GeoPackage geoPackage)
            throws SQLException {

        SpatialReferenceSystem srs = geoPackage.getSpatialReferenceSystemDao()
                .getOrCreateCode(ProjectionConstants.AUTHORITY_EPSG,
                        ProjectionConstants.EPSG_WORLD_GEODETIC_SYSTEM);

        GeometryColumns geometryColumns = new GeometryColumns();
        geometryColumns.setId(new TableColumnKey(TABLE_NAME, COLUMN_NAME));
        geometryColumns.setGeometryType(GeometryType.POLYGON);
        geometryColumns.setSrs(srs);

        FeatureTable table = geoPackage.createFeatureTable(FeatureTableMetadata
                .create(geometryColumns, BoundingBox.worldWGS84()));

        return geoPackage.getFeatureDao(table);
    }

}
