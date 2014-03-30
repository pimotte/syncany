/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2014 Philipp C. Heckel <philipp.heckel@gmail.com> 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.tests.operations;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.junit.Test;
import org.syncany.connection.plugins.local.LocalConnection;
import org.syncany.database.DatabaseConnectionFactory;
import org.syncany.operations.CleanupOperation.CleanupOperationOptions;
import org.syncany.operations.CleanupOperation.CleanupOperationResult;
import org.syncany.operations.CleanupOperation.CleanupResultCode;
import org.syncany.operations.StatusOperation.StatusOperationOptions;
import org.syncany.operations.up.UpOperationOptions;
import org.syncany.tests.util.TestAssertUtil;
import org.syncany.tests.util.TestClient;
import org.syncany.tests.util.TestConfigUtil;

public class CleanupOperationTest {
	@Test
	public void testEasyCleanup() throws Exception {
		// Setup
		LocalConnection testConnection = (LocalConnection) TestConfigUtil.createTestLocalConnection();
		TestClient clientA = new TestClient("A", testConnection);
		TestClient clientB = new TestClient("B", testConnection);
		
		CleanupOperationOptions options = new CleanupOperationOptions();
		options.setMergeRemoteFiles(false);
		options.setRemoveOldVersions(true);
		options.setRepackageMultiChunks(false);
		options.setKeepVersionsCount(2);

		// Run
		
		// A: Create some file versions
		clientA.createNewFile("someotherfile.jpg");	// These two files' chunks will be in one multichunk	
		clientA.createNewFile("file.jpg");		    // Only one of the chunks will be needed after cleanup!
		                                            // The multichunk will be 50% useless
		for (int i=1; i<=4; i++) {
			clientA.changeFile("file.jpg");
			clientA.upWithForceChecksum();			
		}
		
		clientA.createNewFile("otherfile.txt");
		for (int i=1; i<=3; i++) {
			clientA.changeFile("otherfile.txt");
			clientA.upWithForceChecksum();			
		}
		
		clientA.createNewFile("deletedfile.txt");
		for (int i=1; i<=3; i++) {
			clientA.changeFile("deletedfile.txt");
			clientA.upWithForceChecksum();			
		}		
		clientA.deleteFile("deletedfile.txt");
		clientA.upWithForceChecksum();			
		
		java.sql.Connection databaseConnectionA = DatabaseConnectionFactory.createConnection(clientA.getDatabaseFile());		
		assertEquals("12", TestAssertUtil.runSqlQuery("select count(*) from fileversion", databaseConnectionA));
		assertEquals("11", TestAssertUtil.runSqlQuery("select count(*) from chunk", databaseConnectionA));
		assertEquals("10", TestAssertUtil.runSqlQuery("select count(*) from multichunk", databaseConnectionA));
		assertEquals("11", TestAssertUtil.runSqlQuery("select count(*) from filecontent", databaseConnectionA));
		assertEquals("4", TestAssertUtil.runSqlQuery("select count(distinct id) from filehistory", databaseConnectionA));

		// B: Sync down by other client
		clientB.down();
		
		java.sql.Connection databaseConnectionB = DatabaseConnectionFactory.createConnection(clientB.getDatabaseFile());		
		assertEquals("12", TestAssertUtil.runSqlQuery("select count(*) from fileversion", databaseConnectionB));
		assertEquals("11", TestAssertUtil.runSqlQuery("select count(*) from chunk", databaseConnectionB));
		assertEquals("10", TestAssertUtil.runSqlQuery("select count(*) from multichunk", databaseConnectionB));
		assertEquals("11", TestAssertUtil.runSqlQuery("select count(*) from filecontent", databaseConnectionB));
		assertEquals("4", TestAssertUtil.runSqlQuery("select count(distinct id) from filehistory", databaseConnectionB));
		
		// A: Cleanup this mess (except for two)     <<<< This is the interesting part!!! <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
		CleanupOperationResult cleanupOperationResult = clientA.cleanup(options);		
		assertEquals(CleanupResultCode.OK, cleanupOperationResult.getResultCode());
		assertEquals(0, cleanupOperationResult.getMergedDatabaseFilesCount());
		assertEquals(5, cleanupOperationResult.getRemovedMultiChunks().size());
		assertEquals(3, cleanupOperationResult.getRemovedOldVersionsCount());
		
		// 2 versions for "file.jpg", 2 versions for "otherfile.txt" and one version for "someotherfile.jpg"
		assertEquals("5", TestAssertUtil.runSqlQuery("select count(*) from fileversion", databaseConnectionA));
		assertEquals("7", TestAssertUtil.runSqlQuery("select sum(version) from fileversion where path='file.jpg'", databaseConnectionA)); // 3+4
		assertEquals("5", TestAssertUtil.runSqlQuery("select sum(version) from fileversion where path='otherfile.txt'", databaseConnectionA)); // 2+3
		assertEquals("1", TestAssertUtil.runSqlQuery("select sum(version) from fileversion where path='someotherfile.jpg'", databaseConnectionA));
				
		// 5 chunks remain; one was obsolete so we removed it!
		assertEquals("5", TestAssertUtil.runSqlQuery("select count(*) from chunk", databaseConnectionA));
		
		// 6 chunks in 5 multichunks
		assertEquals("5", TestAssertUtil.runSqlQuery("select count(*) from multichunk", databaseConnectionA));
		assertEquals("5", TestAssertUtil.runSqlQuery("select count(*) from filecontent", databaseConnectionA));
		assertEquals("3", TestAssertUtil.runSqlQuery("select count(distinct id) from filehistory", databaseConnectionA));
		
		// Test the repo
		assertEquals(5, new File(testConnection.getRepositoryPath()+"/multichunks/").list().length);
		assertEquals(12, new File(testConnection.getRepositoryPath()+"/databases/").list().length); 

		// B: Sync down cleanup
		clientB.down();
		TestAssertUtil.assertSqlDatabaseEquals(clientA.getDatabaseFile(), clientB.getDatabaseFile());
		
		// Tear down
		clientA.deleteTestData();	
		clientB.deleteTestData();
	}
	
	@Test
	public void testCleanupFailsBecauseOfLocalChanges() throws Exception {
		// Setup
		LocalConnection testConnection = (LocalConnection) TestConfigUtil.createTestLocalConnection();
		TestClient clientA = new TestClient("A", testConnection);
		TestClient clientB = new TestClient("B", testConnection);
		
		StatusOperationOptions statusOptions = new StatusOperationOptions();
		statusOptions.setForceChecksum(true);
		
		CleanupOperationOptions cleanupOptions = new CleanupOperationOptions();
		cleanupOptions.setStatusOptions(statusOptions);
		cleanupOptions.setMergeRemoteFiles(false);
		cleanupOptions.setRemoveOldVersions(true);
		cleanupOptions.setRepackageMultiChunks(false);
		cleanupOptions.setKeepVersionsCount(2);

		// Run
		
		// A: Create some file versions
		clientA.createNewFile("file.jpg");		
		for (int i=1; i<=4; i++) {
			clientA.changeFile("file.jpg");
			clientA.upWithForceChecksum();			
		}
		
		// B: Sync down, add something
		clientB.down();
		
		clientB.changeFile("file.jpg");
		
		CleanupOperationResult cleanupOperationResult = clientB.cleanup(cleanupOptions);
		assertEquals(CleanupResultCode.NOK_LOCAL_CHANGES, cleanupOperationResult.getResultCode());
		assertEquals(0, cleanupOperationResult.getMergedDatabaseFilesCount());
		assertEquals(0, cleanupOperationResult.getRemovedMultiChunks().size());
		assertEquals(0, cleanupOperationResult.getRemovedOldVersionsCount());
		
		// Tear down
		clientA.deleteTestData();
		clientB.deleteTestData();	
	}
	
	@Test
	public void testCleanupFailsBecauseOfRemoteChanges() throws Exception {
		// Setup
		LocalConnection testConnection = (LocalConnection) TestConfigUtil.createTestLocalConnection();
		TestClient clientA = new TestClient("A", testConnection);
		TestClient clientB = new TestClient("B", testConnection);
		
		CleanupOperationOptions options = new CleanupOperationOptions();
		options.setMergeRemoteFiles(false);
		options.setRemoveOldVersions(true);
		options.setRepackageMultiChunks(false);
		options.setKeepVersionsCount(2);

		// Run
		
		// A: Create some file versions
		clientA.createNewFile("file.jpg");		
		for (int i=1; i<=4; i++) {
			clientA.changeFile("file.jpg");
			clientA.upWithForceChecksum();			
		}
		
		// B: Sync down, add something
		clientB.down();
		
		// A: Add something
		clientA.changeFile("file.jpg");
		clientA.upWithForceChecksum();
		
		// B: Cleanup
		CleanupOperationResult cleanupOperationResult = clientB.cleanup(options);
		assertEquals(CleanupResultCode.NOK_REMOTE_CHANGES, cleanupOperationResult.getResultCode());
		assertEquals(0, cleanupOperationResult.getMergedDatabaseFilesCount());
		assertEquals(0, cleanupOperationResult.getRemovedMultiChunks().size());
		assertEquals(0, cleanupOperationResult.getRemovedOldVersionsCount());
		
		// Tear down
		clientA.deleteTestData();
		clientB.deleteTestData();	
	}
	
	@Test
	public void testCleanupNoChanges() throws Exception {
		// Setup
		LocalConnection testConnection = (LocalConnection) TestConfigUtil.createTestLocalConnection();
		TestClient clientA = new TestClient("A", testConnection);
		TestClient clientB = new TestClient("B", testConnection);
		
		CleanupOperationOptions options = new CleanupOperationOptions();
		options.setMergeRemoteFiles(false);
		options.setRemoveOldVersions(true);
		options.setRepackageMultiChunks(false);
		options.setKeepVersionsCount(10);       // <<<<<< Different!

		// Run
		
		// A: Create some file versions
		clientA.createNewFile("file.jpg");		
		for (int i=1; i<=4; i++) {
			clientA.changeFile("file.jpg");
			clientA.upWithForceChecksum();			
		}
		
		// B: Sync down, add something
		clientB.down();
				
		// B: Cleanup
		CleanupOperationResult cleanupOperationResult = clientB.cleanup(options);
		assertEquals(CleanupResultCode.OK_NOTHING_DONE, cleanupOperationResult.getResultCode());
		assertEquals(0, cleanupOperationResult.getMergedDatabaseFilesCount());
		assertEquals(0, cleanupOperationResult.getRemovedMultiChunks().size());
		assertEquals(0, cleanupOperationResult.getRemovedOldVersionsCount());
		
		// Tear down
		clientA.deleteTestData();
		clientB.deleteTestData();	
	}
	
	@Test
	public void testCleanupManyUpsAfterCleanup() throws Exception {
		// Setup
		LocalConnection testConnection = (LocalConnection) TestConfigUtil.createTestLocalConnection();
		TestClient clientA = new TestClient("A", testConnection);
		TestClient clientB = new TestClient("B", testConnection);
		
		CleanupOperationOptions options = new CleanupOperationOptions();
		options.setMergeRemoteFiles(false);
		options.setRemoveOldVersions(true);
		options.setRepackageMultiChunks(false);
		options.setKeepVersionsCount(2);       

		// Run
		
		// A: Create some file versions
		clientA.createNewFile("file.jpg");		
		for (int i=1; i<=4; i++) {
			clientA.changeFile("file.jpg");
			clientA.upWithForceChecksum();			
		}
		
		// B: Sync down
		clientB.down();
				
		// A: Cleanup
		CleanupOperationResult cleanupOperationResult = clientA.cleanup(options);
		assertEquals(CleanupResultCode.OK, cleanupOperationResult.getResultCode());
		assertEquals(0, cleanupOperationResult.getMergedDatabaseFilesCount());
		assertEquals(2, cleanupOperationResult.getRemovedMultiChunks().size());
		assertEquals(1, cleanupOperationResult.getRemovedOldVersionsCount());
		
		// A: Continue to upload stuff !  <<<<<<<<<<<<<<<<<<<<< 
		for (int i=1; i<=4; i++) {
			clientA.changeFile("file.jpg");
			clientA.upWithForceChecksum();			
		}

		clientA.createNewFile("file2.jpg");		
		for (int i=1; i<=4; i++) {
			clientA.changeFile("file2.jpg");
			clientA.upWithForceChecksum();			
		}

		// B: Sync down
		clientB.down();
		TestAssertUtil.assertSqlDatabaseEquals(clientA.getDatabaseFile(), clientB.getDatabaseFile());
		
		// Tear down
		clientA.deleteTestData();
		clientB.deleteTestData();	
	}
	
	@Test
	public void testCleanupNoChangeBecauseDirty() throws Exception {
		// Setup
		LocalConnection testConnection = (LocalConnection) TestConfigUtil.createTestLocalConnection();
		TestClient clientA = new TestClient("A", testConnection);
		TestClient clientB = new TestClient("B", testConnection);
		
		CleanupOperationOptions removeOldCleanupOperationOptions = new CleanupOperationOptions();
		removeOldCleanupOperationOptions.setMergeRemoteFiles(false);
		removeOldCleanupOperationOptions.setRemoveOldVersions(true);
		removeOldCleanupOperationOptions.setRepackageMultiChunks(false);
		removeOldCleanupOperationOptions.setKeepVersionsCount(2);
		
		StatusOperationOptions forceChecksumStatusOperationOptions = new StatusOperationOptions();
		forceChecksumStatusOperationOptions.setForceChecksum(true);
		
		UpOperationOptions noCleanupAndForceUpOperationOptions = new UpOperationOptions();
		noCleanupAndForceUpOperationOptions.setCleanupEnabled(false);
		noCleanupAndForceUpOperationOptions.setForceUploadEnabled(true);
		noCleanupAndForceUpOperationOptions.setStatusOptions(forceChecksumStatusOperationOptions);

		// Run
		
		// A: Create some file versions
		clientA.createNewFile("file.jpg");		
		for (int i=1; i<=4; i++) {
			clientA.changeFile("file.jpg");
			clientA.upWithForceChecksum();			
		}
		
		// B: Sync down, add something
		clientB.down();
		
		// A: Change file.jpg (first step in creating a conflict)
		clientA.changeFile("file.jpg");
		clientA.up(noCleanupAndForceUpOperationOptions);
				
		// B: Change file.jpg (second step in creating a conflict)
		clientB.changeFile("file.jpg");
		clientB.up(noCleanupAndForceUpOperationOptions); // << creates conflict
		
		// B: Sync down (creates a local conflict file and marks local changes as DRITY)
		clientB.down(); // << creates DIRTY database entries
		
		// B: Cleanup
		CleanupOperationResult cleanupOperationResult = clientB.cleanup(removeOldCleanupOperationOptions);
		assertEquals(CleanupResultCode.NOK_DIRTY_LOCAL, cleanupOperationResult.getResultCode());
		assertEquals(0, cleanupOperationResult.getMergedDatabaseFilesCount());
		assertEquals(0, cleanupOperationResult.getRemovedMultiChunks().size());
		assertEquals(0, cleanupOperationResult.getRemovedOldVersionsCount());
		
		// Tear down
		clientA.deleteTestData();
		clientB.deleteTestData();	
	}
}
