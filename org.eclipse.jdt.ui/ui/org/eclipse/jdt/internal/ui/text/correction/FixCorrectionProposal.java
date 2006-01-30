/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.text.correction;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.runtime.CoreException;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Shell;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.IContentAssistantExtension2;

import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.TextChange;

import org.eclipse.jdt.core.dom.rewrite.ITrackedNodePosition;

import org.eclipse.jdt.internal.corext.fix.CleanUpRefactoring;
import org.eclipse.jdt.internal.corext.fix.IFix;
import org.eclipse.jdt.internal.corext.fix.LinkedFix;
import org.eclipse.jdt.internal.corext.fix.PositionGroup;
import org.eclipse.jdt.internal.corext.refactoring.changes.CompilationUnitChange;

import org.eclipse.jdt.ui.text.java.IInvocationContext;

import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.fix.ICleanUp;
import org.eclipse.jdt.internal.ui.refactoring.RefactoringExecutionHelper;
import org.eclipse.jdt.internal.ui.util.BusyIndicatorRunnableContext;

/**
 * A correction proposal which uses an {@link IFix} to
 * fix a problem. A fix correction proposal may have an {@link ICleanUp}
 * attached which can be executed instead of the provided IFix.
 */
public class FixCorrectionProposal extends LinkedCorrectionProposal implements ICompletionProposalExtension2 {
	private final IFix fFix;
	private final ICleanUp fCleanUp;
	private final IContentAssistantExtension2 fAssistant;
	
	public FixCorrectionProposal(IFix fix, ICleanUp cleanUp, int relevance, Image image, IInvocationContext context) {
		super(fix.getDescription(), fix.getCompilationUnit(), null, relevance, image);
		fFix= fix;
		fCleanUp= cleanUp;
		if (context instanceof AssistContext) {
			AssistContext assistContext= (AssistContext)context;
			if (assistContext.getContentAssistant() instanceof IContentAssistantExtension2) {
				fAssistant= (IContentAssistantExtension2)assistContext.getContentAssistant();
			} else {
				fAssistant= null;
			}
		} else {
			fAssistant= null;
		}
	}
	
	public ICleanUp getCleanUp() {
		return fCleanUp;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.ui.text.correction.CUCorrectionProposal#createTextChange()
	 */
	protected TextChange createTextChange() throws CoreException {
		IFix fix= fFix;
		TextChange createChange= fix.createChange();

		if (fix instanceof LinkedFix) {
			LinkedFix linkedFix= (LinkedFix)fix;
			
			PositionGroup[] positionGroups= linkedFix.getPositionGroups();
			for (int i= 0; i < positionGroups.length; i++) {
				PositionGroup group= positionGroups[i];
				String groupId= group.getGroupId();

				ITrackedNodePosition firstPosition= group.getFirstPosition();
				ITrackedNodePosition[] positions= group.getPositions();
				for (int j= 0; j < positions.length; j++) {
					addLinkedPosition(positions[j], positions[j] == firstPosition, groupId);
				}
				String[] proposals= group.getProposals();
				String[] displayStrings= group.getDisplayStrings();
				for (int j= 0; j < proposals.length; j++) {
					String proposal= proposals[j];
					String displayString= displayStrings[j];
					
					if (proposal == null)
						proposal= displayString;
					
					if (displayString == null)
						displayString= proposal;
					
					addLinkedPositionProposal(groupId, displayString, proposal, null);
				}
			}
			
			ITrackedNodePosition endPosition= linkedFix.getEndPosition();
			if (endPosition != null) {
				setEndPosition(endPosition);
			}
		}
		
		if (createChange == null)
			return new CompilationUnitChange("", getCompilationUnit()); //$NON-NLS-1$
		
		return createChange;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jface.text.contentassist.ICompletionProposalExtension2#apply(org.eclipse.jface.text.ITextViewer, char, int, int)
	 */
	public void apply(ITextViewer viewer, char trigger, int stateMask, int offset) {
		if (stateMask == SWT.CONTROL && fCleanUp != null){
			CleanUpRefactoring refactoring= new CleanUpRefactoring();
			refactoring.addCompilationUnit(getCompilationUnit());
			refactoring.addCleanUp(fCleanUp);
			
			int stopSeverity= RefactoringCore.getConditionCheckingFailedSeverity();
			Shell shell= JavaPlugin.getActiveWorkbenchShell();
			BusyIndicatorRunnableContext context= new BusyIndicatorRunnableContext();
			RefactoringExecutionHelper executer= new RefactoringExecutionHelper(refactoring, stopSeverity, false, shell, context);
			try {
				executer.perform();
			} catch (InterruptedException e) {
			} catch (InvocationTargetException e) {
				JavaPlugin.log(e);
			}
			return;
		}
		apply(viewer.getDocument());
	}

	public void selected(ITextViewer viewer, boolean smartToggle) {
		if (fAssistant != null) {
			if (fCleanUp != null) {
				fAssistant.setStatusMessage(CorrectionMessages.FixCorrectionProposal_HitCtrlEnter_description);
			} else {
				fAssistant.setStatusMessage(""); //$NON-NLS-1$
			}
		}
	}

	public void unselected(ITextViewer viewer) {
		if (fAssistant != null) {
			fAssistant.setStatusMessage(""); //$NON-NLS-1$
		}
	}

	public boolean validate(IDocument document, int offset, DocumentEvent event) {
		return false;
	}

}
