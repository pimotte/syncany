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
package org.syncany.connection.plugins.unreliable_local;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.syncany.config.Config;
import org.syncany.connection.plugins.StorageException;
import org.syncany.connection.plugins.TransferManager;
import org.syncany.connection.plugins.local.LocalConnection;

/**
 *
 * @author Philipp C. Heckel
 */
public class UnreliableLocalConnection extends LocalConnection {
	private int totalOperationCounter;
	private Map<String, Integer> typeOperationCounters;
	private List<String> failingOperationPatterns;	

	public UnreliableLocalConnection() {
        this.totalOperationCounter = 0;
        this.typeOperationCounters = new HashMap<String, Integer>();
        this.failingOperationPatterns = new ArrayList<String>();
	}

    @Override
    public TransferManager createTransferManager() {
        return new UnreliableLocalTransferManager(this);
    }

	public List<String> getFailingOperationPatterns() {
		return failingOperationPatterns;
	}

	public void setFailingOperationPatterns(List<String> failingOperationPatterns) {
		this.failingOperationPatterns = failingOperationPatterns;
	}

	public int getTotalOperationCounter() {
		return totalOperationCounter;
	}

	public void setTotalOperationCounter(int totalOperationCounter) {
		this.totalOperationCounter = totalOperationCounter;
	}

	public Map<String, Integer> getTypeOperationCounters() {
		return typeOperationCounters;
	}

	public void setTypeOperationCounters(Map<String, Integer> typeOperationCounters) {
		this.typeOperationCounters = typeOperationCounters;
	}
	
	public void increaseTotalOperationCounter() {
		totalOperationCounter++;
	}		
	
	@Override
	public void init(Config config, Map<String, String> optionValues) throws StorageException {
		//Skip validation, because we actually don't use an OptionSpec here
		//getOptionSpecs().validate(optionValues);
		repositoryPath = new File(optionValues.get("path"));
		failingOperationPatterns = new ArrayList<String>(Arrays.asList(optionValues.get("patterns").split(",")));
		this.config = config;
	}
}
