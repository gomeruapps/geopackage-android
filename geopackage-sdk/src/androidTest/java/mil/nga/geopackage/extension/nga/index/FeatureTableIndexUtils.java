package mil.nga.geopackage.extension.nga.index;

import com.j256.ormlite.dao.CloseableIterator;

import junit.framework.TestCase;

import java.sql.SQLException;
import java.util.Date;
import java.util.List;

import mil.nga.geopackage.BoundingBox;
import mil.nga.geopackage.GeoPackage;
import mil.nga.geopackage.TestUtils;
import mil.nga.geopackage.extension.ExtensionScopeType;
import mil.nga.geopackage.extension.Extensions;
import mil.nga.geopackage.extension.ExtensionsDao;
import mil.nga.geopackage.features.user.FeatureCursor;
import mil.nga.geopackage.features.user.FeatureDao;
import mil.nga.geopackage.features.user.FeatureRow;
import mil.nga.geopackage.geom.GeoPackageGeometryData;
import mil.nga.geopackage.io.TestGeoPackageProgress;
import mil.nga.proj.Projection;
import mil.nga.proj.ProjectionConstants;
import mil.nga.proj.ProjectionFactory;
import mil.nga.proj.ProjectionTransform;
import mil.nga.sf.GeometryEnvelope;
import mil.nga.sf.Point;
import mil.nga.sf.util.GeometryEnvelopeBuilder;

/**
 * Feature Table Index Utility test methods
 *
 * @author osbornb
 */
public class FeatureTableIndexUtils {

    /**
     * Test read
     *
     * @param geoPackage
     * @throws Exception
     */
    public static void testIndex(GeoPackage geoPackage) throws Exception {

        // Test indexing each feature table
        List<String> featureTables = geoPackage.getFeatureTables();
        for (String featureTable : featureTables) {

            FeatureDao featureDao = geoPackage.getFeatureDao(featureTable);
            FeatureTableIndex featureTableIndex = new FeatureTableIndex(
                    geoPackage, featureDao);

            // Determine how many features have geometry envelopes or geometries
            int expectedCount = 0;
            FeatureRow testFeatureRow = null;
            FeatureCursor featureCursor = featureDao.queryForAll();
            while (featureCursor.moveToNext()) {
                FeatureRow featureRow = featureCursor.getRow();
                if (featureRow.getGeometryEnvelope() != null) {
                    expectedCount++;
                    // Randomly choose a feature row with Geometry for testing
                    // queries later
                    if (testFeatureRow == null) {
                        testFeatureRow = featureRow;
                    } else if (Math.random() < (1.0 / featureCursor
                            .getCount())) {
                        testFeatureRow = featureRow;
                    }
                }
            }
            featureCursor.close();

            if (featureTableIndex.isIndexed()) {
                featureTableIndex.deleteIndex();
            }

            TestCase.assertFalse(featureTableIndex.isIndexed());
            TestCase.assertNull(featureTableIndex.getLastIndexed());
            Date currentDate = new Date();

            TestUtils.validateGeoPackage(geoPackage);

            // Test indexing
            TestGeoPackageProgress progress = new TestGeoPackageProgress();
            featureTableIndex.setProgress(progress);
            int indexCount = featureTableIndex.index();
            TestUtils.validateGeoPackage(geoPackage);

            TestCase.assertEquals(expectedCount, indexCount);
            TestCase.assertEquals(featureDao.count(), progress.getProgress());
            TestCase.assertNotNull(featureTableIndex.getLastIndexed());
            Date lastIndexed = featureTableIndex.getLastIndexed();
            TestCase.assertTrue(lastIndexed.getTime() > currentDate.getTime());

            TestCase.assertTrue(featureTableIndex.isIndexed());
            TestCase.assertEquals(expectedCount, featureTableIndex.count());

            // Test re-indexing, both ignored and forced
            TestCase.assertEquals(0, featureTableIndex.index());
            TestCase.assertEquals(expectedCount, featureTableIndex.index(true));
            TestCase.assertTrue(featureTableIndex.getLastIndexed().getTime() > lastIndexed
                    .getTime());

            // Query for all indexed geometries
            int resultCount = 0;
            CloseableIterator<GeometryIndex> featureTableResults = featureTableIndex
                    .query();
            while (featureTableResults.hasNext()) {
                GeometryIndex geometryIndex = featureTableResults.next();
                validateGeometryIndex(featureTableIndex, geometryIndex);
                resultCount++;
            }
            featureTableResults.close();
            TestCase.assertEquals(expectedCount, resultCount);

            // Test the query by envelope
            GeometryEnvelope envelope = testFeatureRow.getGeometryEnvelope();
            envelope.setMinX(envelope.getMinX() - .000001);
            envelope.setMaxX(envelope.getMaxX() + .000001);
            envelope.setMinY(envelope.getMinY() - .000001);
            envelope.setMaxY(envelope.getMaxY() + .000001);
            if (envelope.hasZ()) {
                envelope.setMinZ(envelope.getMinZ() - .000001);
                envelope.setMaxZ(envelope.getMaxZ() + .000001);
            }
            if (envelope.hasM()) {
                envelope.setMinM(envelope.getMinM() - .000001);
                envelope.setMaxM(envelope.getMaxM() + .000001);
            }
            resultCount = 0;
            boolean featureFound = false;
            TestCase.assertTrue(featureTableIndex.count(envelope) >= 1);
            featureTableResults = featureTableIndex.query(envelope);
            while (featureTableResults.hasNext()) {
                GeometryIndex geometryIndex = featureTableResults.next();
                validateGeometryIndex(featureTableIndex, geometryIndex);
                if (geometryIndex.getGeomId() == testFeatureRow.getId()) {
                    featureFound = true;
                }
                resultCount++;
            }
            featureTableResults.close();
            TestCase.assertTrue(featureFound);
            TestCase.assertTrue(resultCount >= 1);

            // Pick a projection different from the feature dao and project the
            // bounding box
            BoundingBox boundingBox = new BoundingBox(envelope.getMinX() - 1,
                    envelope.getMinY() - 1, envelope.getMaxX() + 1,
                    envelope.getMaxY() + 1);
            Projection projection = null;
            if (!featureDao.getProjection().equals(ProjectionConstants.AUTHORITY_EPSG, ProjectionConstants.EPSG_WORLD_GEODETIC_SYSTEM)) {
                projection = ProjectionFactory
                        .getProjection(ProjectionConstants.EPSG_WORLD_GEODETIC_SYSTEM);
            } else {
                projection = ProjectionFactory
                        .getProjection(ProjectionConstants.EPSG_WEB_MERCATOR);
            }
            ProjectionTransform transform = featureDao.getProjection()
                    .getTransformation(projection);
            BoundingBox transformedBoundingBox = boundingBox
                    .transform(transform);

            // Test the query by projected bounding box
            resultCount = 0;
            featureFound = false;
            TestCase.assertTrue(featureTableIndex.count(transformedBoundingBox,
                    projection) >= 1);
            featureTableResults = featureTableIndex.query(
                    transformedBoundingBox, projection);
            while (featureTableResults.hasNext()) {
                GeometryIndex geometryIndex = featureTableResults.next();
                validateGeometryIndex(featureTableIndex, geometryIndex);
                if (geometryIndex.getGeomId() == testFeatureRow.getId()) {
                    featureFound = true;
                }
                resultCount++;
            }
            featureTableResults.close();
            TestCase.assertTrue(featureFound);
            TestCase.assertTrue(resultCount >= 1);

            // Update a Geometry and update the index of a single feature row
            Point point = new Point(5, 5);
            GeoPackageGeometryData geometryData = GeoPackageGeometryData
                    .create(featureDao.getSrsId(), point);
            testFeatureRow.setGeometry(geometryData);
            TestCase.assertEquals(1, featureDao.update(testFeatureRow));
            Date lastIndexedBefore = featureTableIndex.getLastIndexed();
            TestCase.assertTrue(featureTableIndex.index(testFeatureRow));
            Date lastIndexedAfter = featureTableIndex.getLastIndexed();
            TestCase.assertTrue(lastIndexedAfter.after(lastIndexedBefore));

            // Verify the index was updated for the feature row
            envelope = point.getEnvelope();
            resultCount = 0;
            featureFound = false;
            TestCase.assertTrue(featureTableIndex.count(envelope) >= 1);
            featureTableResults = featureTableIndex.query(envelope);
            while (featureTableResults.hasNext()) {
                GeometryIndex geometryIndex = featureTableResults.next();
                validateGeometryIndex(featureTableIndex, geometryIndex);
                if (geometryIndex.getGeomId() == testFeatureRow.getId()) {
                    featureFound = true;
                }
                resultCount++;
            }
            featureTableResults.close();
            TestCase.assertTrue(featureFound);
            TestCase.assertTrue(resultCount >= 1);
        }

        ExtensionsDao extensionsDao = geoPackage.getExtensionsDao();
        GeometryIndexDao geometryIndexDao = FeatureTableIndex
                .getGeometryIndexDao(geoPackage);
        TableIndexDao tableIndexDao = FeatureTableIndex
                .getTableIndexDao(geoPackage);

        // Delete the extensions for the first half of the feature tables
        boolean everyOther = false;
        for (String featureTable : featureTables.subList(0,
                (int) Math.ceil(featureTables.size() * .5))) {
            FeatureDao featureDao = geoPackage.getFeatureDao(featureTable);
            int geometryCount = geometryIndexDao
                    .queryForTableName(featureTable).size();
            TestCase.assertTrue(geometryCount > 0);
            TestCase.assertNotNull(tableIndexDao.queryForId(featureTable));
            Extensions extensions = extensionsDao.queryByExtension(
                    FeatureTableIndex.EXTENSION_NAME, featureTable,
                    featureDao.getGeometryColumnName());
            TestCase.assertNotNull(extensions);
            TestCase.assertEquals(extensions.getTableName(), featureTable);
            TestCase.assertEquals(extensions.getColumnName(),
                    featureDao.getGeometryColumnName());
            TestCase.assertEquals(extensions.getExtensionName(),
                    FeatureTableIndex.EXTENSION_NAME);
            TestCase.assertEquals(extensions.getAuthor(),
                    FeatureTableIndex.EXTENSION_AUTHOR);
            TestCase.assertEquals(extensions.getExtensionNameNoAuthor(),
                    FeatureTableIndex.EXTENSION_NAME_NO_AUTHOR);
            TestCase.assertEquals(extensions.getDefinition(),
                    FeatureTableIndex.EXTENSION_DEFINITION);
            TestCase.assertEquals(extensions.getScope(),
                    ExtensionScopeType.READ_WRITE);
            FeatureTableIndex featureTableIndex = new FeatureTableIndex(
                    geoPackage, featureDao);
            TestCase.assertTrue(featureTableIndex.isIndexed());
            TestCase.assertEquals(geometryCount, featureTableIndex.count());

            // Test deleting a single geometry index
            if (everyOther) {
                FeatureCursor featureCursor = featureDao.queryForAll();
                while (featureCursor.moveToNext()) {
                    FeatureRow featureRow = featureCursor.getRow();
                    GeoPackageGeometryData geometryData = featureRow.getGeometry();
                    if (geometryData != null
                            && (geometryData.getEnvelope() != null || geometryData
                            .getGeometry() != null)) {
                        featureCursor.close();
                        TestCase.assertEquals(1, featureTableIndex.deleteIndex(featureRow));
                        TestCase.assertEquals(geometryCount - 1, featureTableIndex.count());
                        break;
                    }
                }
                featureCursor.close();
            }

            geoPackage.getExtensionManager().deleteTableExtensions(featureTable);

            TestCase.assertFalse(featureTableIndex.isIndexed());
            TestCase.assertEquals(0,
                    geometryIndexDao.queryForTableName(featureTable).size());
            TestCase.assertNull(tableIndexDao.queryForId(featureTable));
            extensions = extensionsDao.queryByExtension(
                    FeatureTableIndex.EXTENSION_NAME, featureTable,
                    featureDao.getGeometryColumnName());
            TestCase.assertNull(extensions);
            everyOther = !everyOther;
        }

        TestCase.assertTrue(geometryIndexDao.isTableExists());
        TestCase.assertTrue(tableIndexDao.isTableExists());
        TestCase.assertTrue(extensionsDao.queryByExtension(
                FeatureTableIndex.EXTENSION_NAME).size() > 0);

        // Test deleting all NGA extensions
        geoPackage.getExtensionManager().deleteExtensions();

        TestCase.assertFalse(geometryIndexDao.isTableExists());
        TestCase.assertFalse(tableIndexDao.isTableExists());
        TestCase.assertFalse(extensionsDao.isTableExists());

    }

    /**
     * Test table index delete all
     *
     * @param geoPackage
     * @throws SQLException
     */
    public static void testDeleteAll(GeoPackage geoPackage) throws SQLException {

        // Test indexing each feature table
        List<String> featureTables = geoPackage.getFeatureTables();
        for (String featureTable : featureTables) {

            FeatureDao featureDao = geoPackage.getFeatureDao(featureTable);
            FeatureTableIndex featureTableIndex = new FeatureTableIndex(
                    geoPackage, featureDao);

            if (featureTableIndex.isIndexed()) {
                featureTableIndex.deleteIndex();
            }

            TestCase.assertFalse(featureTableIndex.isIndexed());

            TestUtils.validateGeoPackage(geoPackage);

            // Test indexing
            featureTableIndex.index();
            TestUtils.validateGeoPackage(geoPackage);

            TestCase.assertTrue(featureTableIndex.isIndexed());

        }

        ExtensionsDao extensionsDao = geoPackage.getExtensionsDao();
        GeometryIndexDao geometryIndexDao = FeatureTableIndex
                .getGeometryIndexDao(geoPackage);
        TableIndexDao tableIndexDao = FeatureTableIndex
                .getTableIndexDao(geoPackage);

        TestCase.assertTrue(geometryIndexDao.isTableExists());
        TestCase.assertTrue(tableIndexDao.isTableExists());
        TestCase.assertTrue(extensionsDao.queryByExtension(
                FeatureTableIndex.EXTENSION_NAME).size() > 0);

        TestCase.assertTrue(geometryIndexDao.countOf() > 0);
        long count = tableIndexDao.countOf();
        TestCase.assertTrue(count > 0);

        int deleteCount = tableIndexDao.deleteAllCascade();
        TestCase.assertEquals(count, deleteCount);

        TestCase.assertTrue(geometryIndexDao.countOf() == 0);
        TestCase.assertTrue(tableIndexDao.countOf() == 0);
    }

    /**
     * Validate a Geometry Index result
     *
     * @param featureTableIndex
     * @param geometryIndex
     */
    private static void validateGeometryIndex(
            FeatureTableIndex featureTableIndex, GeometryIndex geometryIndex) {
        FeatureRow featureRow = featureTableIndex.getFeatureRow(geometryIndex);
        TestCase.assertNotNull(featureRow);
        TestCase.assertEquals(featureTableIndex.getTableName(),
                geometryIndex.getTableName());
        TestCase.assertEquals(geometryIndex.getGeomId(), featureRow.getId());
        GeometryEnvelope envelope = featureRow.getGeometryEnvelope();

        TestCase.assertNotNull(envelope);

        TestCase.assertEquals(envelope.getMinX(), geometryIndex.getMinX());
        TestCase.assertEquals(envelope.getMaxX(), geometryIndex.getMaxX());
        TestCase.assertEquals(envelope.getMinY(), geometryIndex.getMinY());
        TestCase.assertEquals(envelope.getMaxY(), geometryIndex.getMaxY());
        if (envelope.isHasZ()) {
            TestCase.assertEquals(envelope.getMinZ(), geometryIndex.getMinZ());
            TestCase.assertEquals(envelope.getMaxZ(), geometryIndex.getMaxZ());
        } else {
            TestCase.assertNull(geometryIndex.getMinZ());
            TestCase.assertNull(geometryIndex.getMaxZ());
        }
        if (envelope.isHasM()) {
            TestCase.assertEquals(envelope.getMinM(), geometryIndex.getMinM());
            TestCase.assertEquals(envelope.getMaxM(), geometryIndex.getMaxM());
        } else {
            TestCase.assertNull(geometryIndex.getMinM());
            TestCase.assertNull(geometryIndex.getMaxM());
        }
    }

}
