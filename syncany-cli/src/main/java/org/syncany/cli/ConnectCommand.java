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
package org.syncany.cli;

import static java.util.Arrays.asList;

import java.util.List;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import org.syncany.config.to.ConfigTO;
import org.syncany.config.to.ConfigTO.ConnectionTO;
import org.syncany.connection.plugins.StorageTestResult;
import org.syncany.operations.init.ConnectOperationListener;
import org.syncany.operations.init.ConnectOperationOptions;
import org.syncany.operations.init.ConnectOperationOptions.ConnectOptionsStrategy;
import org.syncany.operations.init.ConnectOperationResult;
import org.syncany.operations.init.ConnectOperationResult.ConnectResultCode;

public class ConnectCommand extends AbstractInitCommand implements ConnectOperationListener {
	private static final int MAX_RETRY_PASSWORD_COUNT = 3;
	private int retryPasswordCount = 0;
	
	public ConnectCommand() {
		super();
	}

	@Override
	public CommandScope getRequiredCommandScope() {
		return CommandScope.UNINITIALIZED_LOCALDIR;
	}

	@Override
	public int execute(String[] operationArgs) throws Exception {
		boolean retryNeeded = true;
		boolean performOperation = true;

		ConnectOperationOptions operationOptions = parseConnectOptions(operationArgs);

		while (retryNeeded && performOperation) {
			ConnectOperationResult operationResult = client.connect(operationOptions, this);
			printResults(operationResult);

			retryNeeded = operationResult.getResultCode() != ConnectResultCode.OK
					&& operationResult.getResultCode() != ConnectResultCode.NOK_DECRYPT_ERROR;

			if (retryNeeded) {
				performOperation = isInteractive && askRetryConnection();

				if (performOperation) {
					updateConnectionTO(operationOptions.getConfigTO().getConnectionTO());
				}
			}
		}

		return 0;
	}

	private ConnectOperationOptions parseConnectOptions(String[] operationArguments) throws OptionException, Exception {
		ConnectOperationOptions operationOptions = new ConnectOperationOptions();

		OptionParser parser = new OptionParser();
		OptionSpec<String> optionPlugin = parser.acceptsAll(asList("P", "plugin")).withRequiredArg();
		OptionSpec<String> optionPluginOpts = parser.acceptsAll(asList("o", "plugin-option")).withRequiredArg();
		OptionSpec<Void> optionNonInteractive = parser.acceptsAll(asList("I", "no-interaction"));

		OptionSet options = parser.parse(operationArguments);
		List<?> nonOptionArgs = options.nonOptionArguments();

		// --no-interaction
		isInteractive = !options.has(optionNonInteractive);

		// Plugin
		ConnectionTO connectionTO = null;

		if (nonOptionArgs.size() == 1) {
			String connectLink = (String) nonOptionArgs.get(0);

			operationOptions.setStrategy(ConnectOptionsStrategy.CONNECTION_LINK);
			operationOptions.setConnectLink(connectLink);

			connectionTO = null;
		}
		else if (nonOptionArgs.size() == 0) {
			operationOptions.setStrategy(ConnectOptionsStrategy.CONNECTION_TO);
			operationOptions.setConnectLink(null);

			connectionTO = createConnectionTOFromOptions(options, optionPlugin, optionPluginOpts, optionNonInteractive);
		}
		else {
			throw new Exception("Invalid syntax.");
		}

		ConfigTO configTO = createConfigTO(connectionTO);
		
		operationOptions.setLocalDir(localDir);
		operationOptions.setConfigTO(configTO);

		return operationOptions;
	}

	private void printResults(ConnectOperationResult operationResult) {
		if (operationResult.getResultCode() == ConnectResultCode.OK) {
			out.println();
			out.println("Repository connected, and local folder initialized.");
			out.println("You can now use the 'syncany' command to sync your files.");
			out.println();
		}
		else if (operationResult.getResultCode() == ConnectResultCode.NOK_TEST_FAILED) {
			StorageTestResult testResult = operationResult.getTestResult();
			out.println();			

			if (!testResult.isTargetCanConnect()) {
				out.println("ERROR: Cannot connect to the repository, because the connection to the storage backend failed.");
				out.println("       Possible reasons for this could be connectivity issues (are you connect to the Internet?),");
				out.println("       or invalid user credentials (are username/password valid?).");
			}
			else if (!testResult.isTargetExists()) {
				out.println("ERROR: Cannot connect to the repository, because the target does not exist.");
				out.println("       Please check if it really exists and if you can read from it / write to it.");
			}
			else if (!testResult.isTargetCanWrite()) {								
				out.println("ERROR: Cannot connect to the repository, because the target is not writable. This is probably");
				out.println("       a permission issue (does the user have write permissions to the target?).");
			}
			else if (!testResult.isRepoFileExists()) {
				out.println("ERROR: Cannot connect to the repository, because no repo file was found ('syncany' file).");
				out.println("       Are you sure that this is a valid Syncany repository? Use 'sy init' to create a new one.");
			}
			else {
				out.println("ERROR: Cannot connect to the repository.");
			}
			
			out.println();
			printTestResult(testResult);			
		}		
		else if (operationResult.getResultCode() == ConnectResultCode.NOK_DECRYPT_ERROR) {
			out.println();
			out.println("ERROR: Invalid password or corrupt ciphertext.");		
			out.println();
			out.println("The reason for this might be an invalid password, or that the");
			out.println("link/files have been tampered with.");
			out.println();
		}
		else {
			out.println();
			out.println("ERROR: Cannot connect to repository. Unknown error code: " + operationResult);
			out.println();
		}
	}

	@Override
	public String askPassword() {
		out.println();

		char[] passwordChars = console.readPassword("Password: ");
		return new String(passwordChars);
	}

	@Override
	public void notifyCreateMasterKey() {
		out.println();
		out.println("Creating master key from password (this might take a while) ...");
	}

	@Override
	public boolean askRetryPassword() {
		retryPasswordCount++;		
		
		if (retryPasswordCount < MAX_RETRY_PASSWORD_COUNT) {
			int triesLeft = MAX_RETRY_PASSWORD_COUNT-retryPasswordCount;
			String triesLeftStr = triesLeft != 1 ? triesLeft + " tries left." : "Last chance.";  

			out.println("ERROR: Invalid password or corrupt ciphertext. " + triesLeftStr);
			return true;
		}
		else {
			return false;
		}
	}
}
