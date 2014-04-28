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
package org.syncany.operations.init;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.syncany.config.Config;
import org.syncany.config.to.ConfigTO;
import org.syncany.config.to.MasterTO;
import org.syncany.config.to.RepoTO;
import org.syncany.connection.plugins.MasterRemoteFile;
import org.syncany.connection.plugins.RepoRemoteFile;
import org.syncany.connection.plugins.StorageException;
import org.syncany.connection.plugins.StorageTestResult;
import org.syncany.connection.plugins.TransferManager;
import org.syncany.crypto.CipherUtil;
import org.syncany.crypto.SaltedSecretKey;
import org.syncany.operations.init.InitOperationResult.InitResultCode;

/**
 * The init operation initializes a new repository at a given remote storage
 * location. Its responsibilities include:
 * 
 * <ul>
 *   <li>Generating a master key from the user password (if encryption is enabled)
 *       using the {@link CipherUtil#createMasterKey(String) createMasterKey()} method</li>
 *   <li>Creating the local Syncany folder structure in the local directory (.syncany 
 *       folder and the sub-structure).</li>
 *   <li>Initializing the remote storage (creating folder-structure, if necessary)
 *       using the transfer manager's {@link TransferManager#init()} method.</li>
 *   <li>Creating a new repo and master file using {@link RepoTO} and {@link MasterTO},
 *       saving them locally and uploading them to the remote repository.</li>
 * </ul> 
 *   
 * @author Philipp C. Heckel <philipp.heckel@gmail.com>
 */
public class InitOperation extends AbstractInitOperation {
    private static final Logger logger = Logger.getLogger(InitOperation.class.getSimpleName());  
    
    private InitOperationOptions options;
    private InitOperationResult result;
    private InitOperationListener listener;
    private TransferManager transferManager;
    
    public InitOperation(InitOperationOptions options, InitOperationListener listener) {
        super(null);
        
        this.options = options;
        this.result = null;
        this.listener = listener;
    }        
            
    @Override
    public InitOperationResult execute() throws Exception {
		logger.log(Level.INFO, "");
		logger.log(Level.INFO, "Running 'Init'");
		logger.log(Level.INFO, "--------------------------------------------");                      

		transferManager = createTransferManager(options.getConfigTO().getConnectionTO());
		
		// Test the repo
		if (!performRepoTest()) {
			logger.log(Level.INFO, "- Connecting to the repo failed, repo already exists or cannot be created: " + result.getResultCode());			
			return result;
		}

		logger.log(Level.INFO, "- Connecting to the repo was successful");

		// Ask password (if needed)
		String masterKeyPassword = null;
		
		if (options.isEncryptionEnabled()) {
			masterKeyPassword = getOrAskPassword();
		}	
		
		// Create local .syncany directory
		File appDir = createAppDirs(options.getLocalDir());	// TODO [medium] create temp dir first, ask password cannot be done after
		File configFile = new File(appDir, Config.FILE_CONFIG);
		File repoFile = new File(appDir, Config.FILE_REPO);
		File masterFile = new File(appDir, Config.FILE_MASTER);
		
		// Save config.xml and repo file		
		if (options.isEncryptionEnabled()) {
			SaltedSecretKey masterKey = createMasterKeyFromPassword(masterKeyPassword); // This takes looong!			
			options.getConfigTO().setMasterKey(masterKey);
			
			writeXmlFile(new MasterTO(masterKey.getSalt()), masterFile);
			writeEncryptedXmlFile(options.getRepoTO(), repoFile, options.getCipherSpecs(), masterKey);				
		}	
		else {
			writeXmlFile(options.getRepoTO(), repoFile);
		}	
		
		writeXmlFile(options.getConfigTO(), configFile);

		logger.log(Level.INFO, "Uploading local repository");
		
		// Make remote changes
		initRemoteRepository();		
		try {
			if (options.isEncryptionEnabled()) {
				uploadMasterFile(masterFile, transferManager);
			}
			
			uploadRepoFile(repoFile, transferManager);
		}
		catch (StorageException|IOException e) {
			cleanLocalRepository(e);
		}
		
		
		// Make link		
		GenlinkOperationResult genlinkOperationResult = generateLink(options.getConfigTO());
					
		return new InitOperationResult(InitResultCode.OK, genlinkOperationResult);
    }          
    
	private boolean performRepoTest() {
		boolean testCreateTarget = options.isCreateTarget();
		StorageTestResult testResult = transferManager.test(testCreateTarget);
		
		logger.log(Level.INFO, "Storage test result ist " + testResult);
		
		if (testResult.isTargetExists() && testResult.isTargetCanWrite() && !testResult.isRepoFileExists()) {
			logger.log(Level.INFO, "--> OKAY: Target exists and is writable, but repo doesn't exist. We're good to go!");
			return true;
		}
		else if (testCreateTarget && !testResult.isTargetExists() && testResult.isTargetCanCreate()) {
			logger.log(Level.INFO, "--> OKAY: Target does not exist, but can be created. We're good to go!");
			return true;
		}
		else {
			logger.log(Level.INFO, "--> NOT OKAY: Invalid target/repo state. Operation cannot be continued.");
			result = new InitOperationResult(InitResultCode.NOK_TEST_FAILED, testResult);			
			return false;
		}
	}

	private void initRemoteRepository() throws Exception {
		try {
			transferManager.init(options.isCreateTarget());
		}
		catch (StorageException e) {
			// Storing remotely failed. Remove all the directories and files we just created
			cleanLocalRepository(e);
 		}
	}
	
	private void cleanLocalRepository(Exception e) throws Exception {
		
		try {
			deleteAppDirs(options.getLocalDir());
		}
		catch (Exception e1) {
			throw new StorageException("Couldn't upload to remote repo. Cleanup failed. There may be local directories left");
		}
		
		// TODO [medium] This throws construction is odd and the error message doesn't tell me anything. 
		throw new StorageException("Couldn't upload to remote repo. Cleaned local repository.", e);
	}

	private GenlinkOperationResult generateLink(ConfigTO configTO) throws Exception {
		return new GenlinkOperation(options.getConfigTO()).execute();
	}

	private String getOrAskPassword() throws Exception {
		if (options.getPassword() == null) {
			if (listener == null) {
				throw new RuntimeException("Cannot get password from user interface. No listener.");
			}
			
			return listener.getPasswordCallback();
		}
		else {
			return options.getPassword();
		}		
	}	
	
	private SaltedSecretKey createMasterKeyFromPassword(String masterPassword) throws Exception {
		if (listener != null) {
			listener.notifyGenerateMasterKey();
		}
		
		SaltedSecretKey masterKey = CipherUtil.createMasterKey(masterPassword);
		return masterKey;
	}

	protected boolean repoFileExistsOnRemoteStorage(TransferManager transferManager) throws Exception {
		try {
			Map<String, RepoRemoteFile> repoFileList = transferManager.list(RepoRemoteFile.class);			
			return repoFileList.size() > 0;
		}
		catch (Exception e) {
			throw new Exception("Unable to connect to repository.", e);
		}		
	}
	
	private void uploadMasterFile(File masterFile, TransferManager transferManager) throws Exception {    		
		transferManager.upload(masterFile, new MasterRemoteFile());
	}  
	
	private void uploadRepoFile(File repoFile, TransferManager transferManager) throws Exception {    		
		transferManager.upload(repoFile, new RepoRemoteFile());
	}    	
}