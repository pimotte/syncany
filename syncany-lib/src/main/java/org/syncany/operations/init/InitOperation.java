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
import java.util.List;
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
import org.syncany.connection.plugins.TransferManager;
import org.syncany.connection.plugins.TransferManager.StorageTestResult;
import org.syncany.crypto.CipherSpec;
import org.syncany.crypto.CipherUtil;
import org.syncany.crypto.SaltedSecretKey;
import org.syncany.operations.OperationOptions;
import org.syncany.operations.OperationResult;
import org.syncany.operations.init.GenlinkOperation.GenlinkOperationResult;

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
		
		if (options.isEncryptionEnabled()) {
			uploadMasterFile(masterFile, transferManager);
		}
		
		uploadRepoFile(repoFile, transferManager);
		
		// Make link		
		GenlinkOperationResult genlinkOperationResult = generateLink(options.getConfigTO());
					
		return new InitOperationResult(InitResultCode.OK, genlinkOperationResult);
    }          
    
	private boolean performRepoTest() {
		StorageTestResult repoTestResult = transferManager.test();
		
		switch (repoTestResult) {
		case NO_CONNECTION:
			result = new InitOperationResult(InitResultCode.NOK_NO_CONNECTION);
			return false;
						
		case REPO_EXISTS:
			result = new InitOperationResult(InitResultCode.NOK_REPO_EXISTS);
			return false;
			
		case REPO_EXISTS_BUT_INVALID:
			return true;

		case NO_REPO_CANNOT_CREATE:
			result = new InitOperationResult(InitResultCode.NOK_NO_REPO_CANNOT_CREATE);
			return false;

		case NO_REPO:
			if (!options.isCreateTargetPath()) {
				result = new InitOperationResult(InitResultCode.NOK_NO_REPO_CANNOT_CREATE);
				return false;
			}
			else {
				return true;
			}
			 
		default:
			throw new RuntimeException("Test result "+repoTestResult+" should have been handled before.");
		}		
	}

	private void initRemoteRepository() throws Exception {
		try {
			transferManager.init(options.isCreateTargetPath());
		}
		catch (StorageException e) {
			// Storing remotely failed. Remove all the directories and files we just created
			try {
				deleteAppDirs(options.getLocalDir());
			}
			catch (Exception e1) {
				throw new Exception("StorageException for remote. Cleanup failed. There may be local directories left");
			}
			
			// TODO [medium] This throws construction is odd and the error message doesn't tell me anything. 
			throw new Exception("StorageException for remote. Cleaned local repository.");
 		}
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
	
	public static interface InitOperationListener {
		public String getPasswordCallback();
		public void notifyGenerateMasterKey();
	}	
 
    public static class InitOperationOptions implements OperationOptions {
    	private boolean createTargetPath;
    	private File localDir;
    	private ConfigTO configTO;
    	private RepoTO repoTO;
    	private boolean encryptionEnabled;
    	private List<CipherSpec> cipherSpecs;
    	private String password;
		
		public boolean isCreateTargetPath() {
			return createTargetPath;
		}

		public void setCreateTargetPath(boolean createTargetPath) {
			this.createTargetPath = createTargetPath;
		}

		public File getLocalDir() {
			return localDir;
		}

		public void setLocalDir(File localDir) {
			this.localDir = localDir;
		}

		public ConfigTO getConfigTO() {
			return configTO;
		}
		
		public void setConfigTO(ConfigTO configTO) {
			this.configTO = configTO;
		}
		
		public RepoTO getRepoTO() {
			return repoTO;
		}
		
		public void setRepoTO(RepoTO repoTO) {
			this.repoTO = repoTO;
		}

		public boolean isEncryptionEnabled() {
			return encryptionEnabled;
		}

		public void setEncryptionEnabled(boolean encryptionEnabled) {
			this.encryptionEnabled = encryptionEnabled;
		}

		public List<CipherSpec> getCipherSpecs() {
			return cipherSpecs;
		}

		public void setCipherSpecs(List<CipherSpec> cipherSpecs) {
			this.cipherSpecs = cipherSpecs;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}  						
    }
    
    public enum InitResultCode {
		OK, NOK_REPO_EXISTS, NOK_NO_REPO_CANNOT_CREATE, NOK_NO_CONNECTION
	}
    
    public class InitOperationResult implements OperationResult {
    	private InitResultCode resultCode = InitResultCode.OK;
        private GenlinkOperationResult genLinkResult = null;

        public InitOperationResult(InitResultCode resultCode) {
        	this.resultCode = resultCode;
        }
        
		public InitOperationResult(InitResultCode resultCode, GenlinkOperationResult genLinkResult) {
			this.resultCode = resultCode;
			this.genLinkResult = genLinkResult;
		}

		public InitResultCode getResultCode() {
			return resultCode;
		}
		
		public GenlinkOperationResult getGenLinkResult() {
			return genLinkResult;
		}              
    }
}