/*******************************************************************************
 * Copyright (c) 2019 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.util.PropertyChangeEvent;

import org.eclipse.jface.text.IDocumentPartitioner;
import org.eclipse.jface.text.rules.IPartitionTokenScanner;

import org.eclipse.ui.texteditor.ITextEditor;

import org.eclipse.jdt.ui.text.IJavaPartitionerManager;
import org.eclipse.jdt.ui.text.IJavaPartitions;

import org.eclipse.jdt.internal.ui.javaeditor.EditorUtility;

public class JavaPartitionerManager implements IJavaPartitionerManager {

	private final static String[] LEGAL_CONTENT_TYPES= new String[] {
			IJavaPartitions.JAVA_DOC,
			IJavaPartitions.JAVA_MULTI_LINE_COMMENT,
			IJavaPartitions.JAVA_SINGLE_LINE_COMMENT,
			IJavaPartitions.JAVA_STRING,
			IJavaPartitions.JAVA_CHARACTER,
			IJavaPartitions.JAVA_MULTI_LINE_STRING
	};

	private static ITextEditor fEditor;

	private Map<ITextEditor, IDocumentPartitioner> fEditorPartitionerMap;

	private Set<FastJavaPartitioner> fPartitionerSet;

	public JavaPartitionerManager() {
		fEditorPartitionerMap= new HashMap<>();
		fPartitionerSet= new HashSet<>();
	}

	@Override
	public IPartitionTokenScanner getPartitionScanner() {
		return new FastJavaPartitionScanner(EditorUtility.getJavaProject(fEditor));
	}

	/**
	 * Factory method for creating a Java-specific document partitioner using this object's
	 * partitions scanner. This method is a convenience method.
	 *
	 * @return a newly created Java document partitioner
	 */
	@Override
	public IDocumentPartitioner createDocumentPartitioner() {
		FastJavaPartitioner partitioner= new FastJavaPartitioner(getPartitionScanner(), LEGAL_CONTENT_TYPES);
		fPartitionerSet.add(partitioner);
		ITextEditor editor= fEditor;
		if(editor != null) {
			fEditorPartitionerMap.put(editor, partitioner);
		}
		return partitioner;
	}

	public void clearEditorInfo(ITextEditor editor) {
		if (editor != null) {
			IDocumentPartitioner partitioner= fEditorPartitionerMap.get(editor);
			fEditorPartitionerMap.remove(editor);
			if (editor == fEditor) {
				fEditor= null;
			}
			fPartitionerSet.remove(partitioner);
		}
	}

	public void dispose() {
		fEditor= null;
		if (fEditorPartitionerMap != null) {
			fEditorPartitionerMap.clear();
			fEditorPartitionerMap= null;
		}
		if (fPartitionerSet != null) {
			fPartitionerSet.clear();
			fPartitionerSet= null;
		}
	}

	public void setEditorInfo(ITextEditor editor) {
		fEditor= editor;
	}

	@SuppressWarnings("unused")
	public boolean affectsBehavior(PropertyChangeEvent event) {
		boolean affectsBehavior= false;
		if (fPartitionerSet != null) {
			for (FastJavaPartitioner partitioner : fPartitionerSet) {
				if (partitioner.hasPreviewEnabledValueChanged()) {
					affectsBehavior= true;
					break;
				}
			}
		}
		return affectsBehavior;
	}

	@SuppressWarnings("unused")
	public void adaptToPreferenceChange(PropertyChangeEvent event) {
		if (fPartitionerSet != null) {
			for (FastJavaPartitioner partitioner : fPartitionerSet) {
				if (partitioner.hasPreviewEnabledValueChanged()) {
					partitioner.cleanAndReConnectDocumentIfNecessary();
				}
			}
		}
	}
}
