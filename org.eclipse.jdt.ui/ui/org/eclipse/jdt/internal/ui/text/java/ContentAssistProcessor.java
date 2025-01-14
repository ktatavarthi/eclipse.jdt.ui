/*******************************************************************************
 * Copyright (c) 2005, 2016 IBM Corporation and others.
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
 *     Paul Fullbright <paul.fullbright@oracle.com> - content assist category enablement - http://bugs.eclipse.org/345213
 *     Marcel Bruch <bruch@cs.tu-darmstadt.de> - [content assist] Allow to re-sort proposals - https://bugs.eclipse.org/bugs/show_bug.cgi?id=350991
 *     Lars Vogel  <lars.vogel@gmail.com> - convert to foreach loop - https://bugs.eclipse.org/bugs/show_bug.cgi?id=406478
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.text.java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.InvalidRegistryObjectException;
import org.eclipse.core.runtime.NullProgressMonitor;

import org.eclipse.jface.action.LegacyActionTools;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.JFaceResources;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionListenerExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContentAssistantExtension2;
import org.eclipse.jface.text.contentassist.IContentAssistantExtension3;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.contentassist.IContextInformationValidator;

import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.keys.IBindingService;

import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;

import org.eclipse.jdt.internal.corext.util.Messages;

import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;

import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.JavaUIMessages;
import org.eclipse.jdt.internal.ui.dialogs.OptionalMessageDialog;
import org.eclipse.jdt.internal.ui.util.Progress;


/**
 * A content assist processor that aggregates the proposals of the
 * {@link org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer}s contributed via the
 * <code>org.eclipse.jdt.ui.javaCompletionProposalComputer</code> extension point.
 * <p>
 * Subclasses may extend:
 * <ul>
 * <li><code>createContext</code> to provide the context object passed to the computers</li>
 * <li><code>createProgressMonitor</code> to change the way progress is reported</li>
 * <li><code>filterAndSort</code> to add sorting and filtering</li>
 * <li><code>getContextInformationValidator</code> to add context validation (needed if any
 * contexts are provided)</li>
 * <li><code>getErrorMessage</code> to change error reporting</li>
 * </ul>
 * </p>
 *
 * @since 3.2
 */
public class ContentAssistProcessor implements IContentAssistProcessor {


	/**
	 * The completion listener class for this processor.
	 *
	 * @since 3.4
	 */
	private final class CompletionListener implements ICompletionListener, ICompletionListenerExtension {
		/*
		 * @see org.eclipse.jface.text.contentassist.ICompletionListener#assistSessionStarted(org.eclipse.jface.text.contentassist.ContentAssistEvent)
		 */
		@Override
		public void assistSessionStarted(ContentAssistEvent event) {
			if (event.processor != ContentAssistProcessor.this) {
				return;
			}

			fIterationGesture= getIterationGesture();
			KeySequence binding= getIterationBinding();

			// This may show the warning dialog if all categories are disabled
			setCategoryIteration();
			for (CompletionProposalCategory cat : getCategoriesToNotify()) {
				cat.sessionStarted();
			}

			fRepetition= 0;
			if (event.assistant instanceof IContentAssistantExtension2) {
				IContentAssistantExtension2 extension= (IContentAssistantExtension2) event.assistant;

				if (fCategoryIteration.size() == 1) {
					extension.setRepeatedInvocationMode(false);
					extension.setShowEmptyList(false);
				} else {
					extension.setRepeatedInvocationMode(true);
					extension.setStatusLineVisible(true);
					extension.setStatusMessage(createIterationMessage());
					extension.setShowEmptyList(true);
					if (extension instanceof IContentAssistantExtension3) {
						IContentAssistantExtension3 ext3= (IContentAssistantExtension3) extension;
						((ContentAssistant) ext3).setRepeatedInvocationTrigger(binding);
					}
				}

			}
		}

		/**
		 * Returns the categories that need to be notified when a session starts and ends.
		 *
		 * @return the current categories
		 * @since 3.8.1
		 */
		private Set<CompletionProposalCategory> getCategoriesToNotify() {
			Set<CompletionProposalCategory> currentCategories= new HashSet<>(fCategories.size());

			// Currently enabled categories for this session
			if (fCategoryIteration != null) {
				Iterator<List<CompletionProposalCategory>> it= fCategoryIteration.iterator();
				while (it.hasNext()) {
					currentCategories.addAll(it.next());
				}
			}

			// Backwards compatibility: notify all categories which have no enablement expression
			for (CompletionProposalCategory cat : fCategories) {
				if (cat.getEnablementExpression() == null) {
					currentCategories.add(cat);
				}
			}

			return currentCategories;
		}

		/*
		 * @see org.eclipse.jface.text.contentassist.ICompletionListener#assistSessionEnded(org.eclipse.jface.text.contentassist.ContentAssistEvent)
		 */
		@Override
		public void assistSessionEnded(ContentAssistEvent event) {
			if (event.processor != ContentAssistProcessor.this) {
				return;
			}

			for (CompletionProposalCategory cat : getCategoriesToNotify()) {
				cat.sessionEnded();
			}

			fSelectedProposal= null;
			fCategoryIteration= null;
			fRepetition= -1;
			fIterationGesture= null;
			if (event.assistant instanceof IContentAssistantExtension2) {
				IContentAssistantExtension2 extension= (IContentAssistantExtension2) event.assistant;
				extension.setShowEmptyList(false);
				extension.setRepeatedInvocationMode(false);
				extension.setStatusLineVisible(false);
				if (extension instanceof IContentAssistantExtension3) {
					IContentAssistantExtension3 ext3= (IContentAssistantExtension3) extension;
					((ContentAssistant) ext3).setRepeatedInvocationTrigger(null);
				}
			}
		}

		/*
		 * @see org.eclipse.jface.text.contentassist.ICompletionListener#selectionChanged(org.eclipse.jface.text.contentassist.ICompletionProposal, boolean)
		 */
		@Override
		public void selectionChanged(ICompletionProposal proposal, boolean smartToggle) {
			fSelectedProposal= proposal;
		}

		/*
		 * @see org.eclipse.jface.text.contentassist.ICompletionListenerExtension#assistSessionRestarted(org.eclipse.jface.text.contentassist.ContentAssistEvent)
		 * @since 3.4
		 */
		@Override
		public void assistSessionRestarted(ContentAssistEvent event) {
			fRepetition= 0;
		}
	}

	/**
	 * Dialog settings key for the "all categories are disabled" warning dialog. See
	 * {@link OptionalMessageDialog}.
	 *
	 * @since 3.3
	 */
	private static final String PREF_WARN_ABOUT_EMPTY_ASSIST_CATEGORY= "EmptyDefaultAssistCategory"; //$NON-NLS-1$

	private static final Comparator<CompletionProposalCategory> ORDER_COMPARATOR= (d1, d2) -> d1.getSortOrder() - d2.getSortOrder();

	private final List<CompletionProposalCategory> fCategories;
	private final String fPartition;
	private final ContentAssistant fAssistant;
	private ICompletionProposal fSelectedProposal;

	private char[] fCompletionAutoActivationCharacters;

	/* cycling stuff */
	private int fRepetition= -1;
	private List<List<CompletionProposalCategory>> fCategoryIteration= null;
	private String fIterationGesture= null;
	private int fNumberOfComputedResults= 0;
	private String fErrorMessage;

	/**
	 * The completion proposal registry.
	 *
	 * @since 3.4
	 */
	private CompletionProposalComputerRegistry fComputerRegistry;

	/**
	 * Flag indicating whether any completion engine associated with this processor requests
	 * resorting of its proposals after filtering is triggered. Filtering is, e.g., triggered when a
	 * user continues typing with an open completion window.
	 *
	 * @since 3.8
	 */
	private boolean fNeedsSortingAfterFiltering;


	public ContentAssistProcessor(ContentAssistant assistant, String partition) {
		Assert.isNotNull(partition);
		Assert.isNotNull(assistant);
		fPartition= partition;
		fComputerRegistry= CompletionProposalComputerRegistry.getDefault();
		fCategories= fComputerRegistry.getProposalCategories();
		fAssistant= assistant;
		fAssistant.addCompletionListener(new CompletionListener());
	}

	/*
	 * @see org.eclipse.jface.text.contentassist.IContentAssistProcessor#computeCompletionProposals(org.eclipse.jface.text.ITextViewer, int)
	 */
	@Override
	public final ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset) {
		long start= JavaPlugin.DEBUG_RESULT_COLLECTOR ? System.currentTimeMillis() : 0;

		clearState();

		IProgressMonitor monitor= createProgressMonitor();
		monitor.beginTask(JavaTextMessages.ContentAssistProcessor_computing_proposals, fCategories.size() + 1);

		ContentAssistInvocationContext context= createContext(viewer, offset);
		long setup= JavaPlugin.DEBUG_RESULT_COLLECTOR ? System.currentTimeMillis() : 0;

		monitor.subTask(JavaTextMessages.ContentAssistProcessor_collecting_proposals);
		List<ICompletionProposal> proposals= collectProposals(viewer, offset, monitor, context);
		long collect= JavaPlugin.DEBUG_RESULT_COLLECTOR ? System.currentTimeMillis() : 0;

		monitor.subTask(JavaTextMessages.ContentAssistProcessor_sorting_proposals);
		if (fNeedsSortingAfterFiltering) {
			setContentAssistSorter();
		} else {
			proposals= sortProposals(proposals, monitor, context);
		}
		fNumberOfComputedResults= proposals.size();
		long filter= JavaPlugin.DEBUG_RESULT_COLLECTOR ? System.currentTimeMillis() : 0;

		ICompletionProposal[] result= proposals.toArray(new ICompletionProposal[proposals.size()]);
		monitor.done();

		if (JavaPlugin.DEBUG_RESULT_COLLECTOR) {
			System.err.println("Code Assist Stats (" + result.length + " proposals)"); //$NON-NLS-1$ //$NON-NLS-2$
			System.err.println("Code Assist (setup):\t" + (setup - start) ); //$NON-NLS-1$
			System.err.println("Code Assist (collect):\t" + (collect - setup) ); //$NON-NLS-1$
			System.err.println("Code Assist (sort):\t" + (filter - collect) ); //$NON-NLS-1$
		}

		return result;
	}

	private void clearState() {
		fErrorMessage=null;
		fNumberOfComputedResults= 0;
	}

	/**
	 * Collects the proposals.
	 *
	 * @param viewer the text viewer
	 * @param offset the offset
	 * @param monitor the progress monitor
	 * @param context the code assist invocation context
	 * @return the list of proposals
	 */
	private List<ICompletionProposal> collectProposals(ITextViewer viewer, int offset, IProgressMonitor monitor, ContentAssistInvocationContext context) {
		boolean needsSortingAfterFiltering= false;
		List<ICompletionProposal> proposals= new ArrayList<>();
		List<CompletionProposalCategory> providers= getCategories();
		for (CompletionProposalCategory cat : providers) {
			List<ICompletionProposal> computed= cat.computeCompletionProposals(context, fPartition, Progress.subMonitor(monitor, 1));
			proposals.addAll(computed);
			needsSortingAfterFiltering= needsSortingAfterFiltering || (cat.isSortingAfterFilteringNeeded() && !computed.isEmpty());
			if (fErrorMessage == null) {
				fErrorMessage= cat.getErrorMessage();
			}
		}
		if (fNeedsSortingAfterFiltering && !needsSortingAfterFiltering) {
			fAssistant.setSorter(null);
		}
		fNeedsSortingAfterFiltering= needsSortingAfterFiltering;
		return proposals;
	}

	/**
	 * Filters and sorts the proposals. The passed list may be modified
	 * and returned, or a new list may be created and returned.
	 *
	 * @param proposals the list of collected proposals (element type:
	 *        {@link ICompletionProposal})
	 * @param monitor a progress monitor
	 * @param context TODO
	 * @return the list of filtered and sorted proposals, ready for
	 *         display (element type: {@link ICompletionProposal})
	 */
	protected List<ICompletionProposal> sortProposals(List<ICompletionProposal> proposals, IProgressMonitor monitor, ContentAssistInvocationContext context) {
		return proposals;
	}

	/*
	 * @see org.eclipse.jface.text.contentassist.IContentAssistProcessor#computeContextInformation(org.eclipse.jface.text.ITextViewer, int)
	 */
	@Override
	public IContextInformation[] computeContextInformation(ITextViewer viewer, int offset) {
		clearState();

		IProgressMonitor monitor= createProgressMonitor();
		monitor.beginTask(JavaTextMessages.ContentAssistProcessor_computing_contexts, fCategories.size() + 1);

		monitor.subTask(JavaTextMessages.ContentAssistProcessor_collecting_contexts);
		List<IContextInformation> proposals= collectContextInformation(viewer, offset, monitor);

		monitor.subTask(JavaTextMessages.ContentAssistProcessor_sorting_contexts);
		List<IContextInformation> filtered= filterAndSortContextInformation(proposals, monitor);
		fNumberOfComputedResults= filtered.size();

		IContextInformation[] result= filtered.toArray(new IContextInformation[filtered.size()]);
		monitor.done();
		return result;
	}

	private List<IContextInformation> collectContextInformation(ITextViewer viewer, int offset, IProgressMonitor monitor) {
		List<IContextInformation> proposals= new ArrayList<>();
		ContentAssistInvocationContext context= createContext(viewer, offset);

		List<CompletionProposalCategory> providers= getCategories();
		for (CompletionProposalCategory cat : providers) {
			List<IContextInformation> computed= cat.computeContextInformation(context, fPartition, Progress.subMonitor(monitor, 1));
			proposals.addAll(computed);
			if (fErrorMessage == null) {
				fErrorMessage= cat.getErrorMessage();
			}
		}

		return proposals;
	}

	/**
	 * Filters and sorts the context information objects. The passed
	 * list may be modified and returned, or a new list may be created
	 * and returned.
	 *
	 * @param contexts the list of collected proposals (element type:
	 *        {@link IContextInformation})
	 * @param monitor a progress monitor
	 * @return the list of filtered and sorted proposals, ready for
	 *         display (element type: {@link IContextInformation})
	 */
	protected List<IContextInformation> filterAndSortContextInformation(List<IContextInformation> contexts, IProgressMonitor monitor) {
		return contexts;
	}

	/**
	 * Sets this processor's set of characters triggering the activation of the
	 * completion proposal computation.
	 *
	 * @param activationSet the activation set
	 */
	public final void setCompletionProposalAutoActivationCharacters(char[] activationSet) {
		fCompletionAutoActivationCharacters= activationSet;
	}


	/*
	 * @see org.eclipse.jface.text.contentassist.IContentAssistProcessor#getCompletionProposalAutoActivationCharacters()
	 */
	@Override
	public final char[] getCompletionProposalAutoActivationCharacters() {
		return fCompletionAutoActivationCharacters;
	}

	/*
	 * @see org.eclipse.jface.text.contentassist.IContentAssistProcessor#getContextInformationAutoActivationCharacters()
	 */
	@Override
	public char[] getContextInformationAutoActivationCharacters() {
		return null;
	}

	/*
	 * @see org.eclipse.jface.text.contentassist.IContentAssistProcessor#getErrorMessage()
	 */
	@Override
	public String getErrorMessage() {
		if (fErrorMessage != null) {
			return fErrorMessage;
		}
		if (fNumberOfComputedResults > 0) {
			return null;
		}
		return JavaUIMessages.JavaEditor_codeassist_noCompletions;
	}

	/*
	 * @see org.eclipse.jface.text.contentassist.IContentAssistProcessor#getContextInformationValidator()
	 */
	@Override
	public IContextInformationValidator getContextInformationValidator() {
		return null;
	}

	/**
	 * Creates a progress monitor.
	 * <p>
	 * The default implementation creates a
	 * <code>NullProgressMonitor</code>.
	 * </p>
	 *
	 * @return a progress monitor
	 */
	protected IProgressMonitor createProgressMonitor() {
		return new NullProgressMonitor();
	}

	/**
	 * Creates the context that is passed to the completion proposal
	 * computers.
	 *
	 * @param viewer the viewer that content assist is invoked on
	 * @param offset the content assist offset
	 * @return the context to be passed to the computers
	 */
	protected ContentAssistInvocationContext createContext(ITextViewer viewer, int offset) {
		return new ContentAssistInvocationContext(viewer, offset);
	}

	private List<CompletionProposalCategory> getCategories() {
		if (fCategoryIteration == null) {
			return fCategories;
		}

		int iteration= fRepetition % fCategoryIteration.size();
		String iterationMessage = createIterationMessage();
		if (Display.getCurrent() != null) {
			fAssistant.setStatusMessage(iterationMessage);
		} else {
			Display.getDefault().asyncExec(() -> fAssistant.setStatusMessage(iterationMessage));
		}
		fAssistant.setEmptyMessage(createEmptyMessage());
		fRepetition++;

//		fAssistant.setShowMessage(fRepetition % 2 != 0);

		return fCategoryIteration.get(iteration);
	}

	// This may show the warning dialog if all categories are disabled
	private void setCategoryIteration() {
		fCategoryIteration= getCategoryIteration();
	}

	private List<List<CompletionProposalCategory>> getCategoryIteration() {
		List<List<CompletionProposalCategory>> sequence= new ArrayList<>();
		sequence.add(getDefaultCategories());
		for (CompletionProposalCategory cat : getSeparateCategories()) {
			sequence.add(Collections.singletonList(cat));
		}
		return sequence;
	}

	private List<CompletionProposalCategory> getDefaultCategories() {
		// default mix - enable all included computers
		List<CompletionProposalCategory> included= getDefaultCategoriesUnchecked();

		if (fComputerRegistry.hasUninstalledComputers(fPartition, included)) {
			if (informUserAboutEmptyDefaultCategory()) {
				// preferences were restored - recompute the default categories
				included= getDefaultCategoriesUnchecked();
			}
			fComputerRegistry.resetUnistalledComputers();
		}

		return included;
	}

	private List<CompletionProposalCategory> getDefaultCategoriesUnchecked() {
		List<CompletionProposalCategory> included= new ArrayList<>();
		for (CompletionProposalCategory category : fCategories) {
			if (checkDefaultEnablement(category)) {
				included.add(category);
			}
		}
		return included;
	}

	/**
	 * Determine whether the category is enabled by default.
	 *
	 * @param category the category to check
	 * @return <code>true</code> if this category is enabled by default, <code>false</code>
	 *         otherwise
	 * @since 3.8
	 */
	protected boolean checkDefaultEnablement(CompletionProposalCategory category) {
		return category.isIncluded() && category.hasComputers(fPartition);
	}

	/**
	 * Informs the user about the fact that there are no enabled categories in the default content
	 * assist set and shows a link to the preferences.
	 *
	 * @return  <code>true</code> if the default should be restored
	 * @since 3.3
	 */
	private boolean informUserAboutEmptyDefaultCategory() {
		if (OptionalMessageDialog.isDialogEnabled(PREF_WARN_ABOUT_EMPTY_ASSIST_CATEGORY)) {
			final Shell shell= JavaPlugin.getActiveWorkbenchShell();
			String title= JavaTextMessages.ContentAssistProcessor_all_disabled_title;
			String message= JavaTextMessages.ContentAssistProcessor_all_disabled_message;
			// see PreferencePage#createControl for the 'defaults' label
			final String restoreButtonLabel= JFaceResources.getString("defaults"); //$NON-NLS-1$
			final String linkMessage= Messages.format(JavaTextMessages.ContentAssistProcessor_all_disabled_preference_link, LegacyActionTools.removeMnemonics(restoreButtonLabel));
			final int restoreId= IDialogConstants.CLIENT_ID + 10;
			final int settingsId= IDialogConstants.CLIENT_ID + 11;
			final OptionalMessageDialog dialog= new OptionalMessageDialog(PREF_WARN_ABOUT_EMPTY_ASSIST_CATEGORY, shell, title, null /* default image */, message, MessageDialog.WARNING, new String[] { restoreButtonLabel, IDialogConstants.CLOSE_LABEL }, 1) {
				/*
				 * @see org.eclipse.jdt.internal.ui.dialogs.OptionalMessageDialog#createCustomArea(org.eclipse.swt.widgets.Composite)
				 */
				@Override
				protected Control createCustomArea(Composite composite) {
					// wrap link and checkbox in one composite without space
					Composite parent= new Composite(composite, SWT.NONE);
					GridLayout layout= new GridLayout();
					layout.marginHeight= 0;
					layout.marginWidth= 0;
					layout.verticalSpacing= 0;
					parent.setLayout(layout);

					Composite linkComposite= new Composite(parent, SWT.NONE);
					layout= new GridLayout();
					layout.marginHeight= convertVerticalDLUsToPixels(IDialogConstants.VERTICAL_MARGIN);
					layout.marginWidth= convertHorizontalDLUsToPixels(IDialogConstants.HORIZONTAL_MARGIN);
					layout.horizontalSpacing= convertHorizontalDLUsToPixels(IDialogConstants.HORIZONTAL_SPACING);
					linkComposite.setLayout(layout);

	        		Link link= new Link(linkComposite, SWT.NONE);
	        		link.setText(linkMessage);
	        		link.addSelectionListener(new SelectionAdapter() {
	        			@Override
						public void widgetSelected(SelectionEvent e) {
	        				setReturnCode(settingsId);
	        				close();
	        			}
	        		});
	        		GridData gridData= new GridData(SWT.FILL, SWT.BEGINNING, true, false);
	        		gridData.widthHint= this.getMinimumMessageWidth();
					link.setLayoutData(gridData);

					// create checkbox and "don't show this message" prompt
					super.createCustomArea(parent);

					return parent;
	        	}

				/*
				 * @see org.eclipse.jface.dialogs.MessageDialog#createButtonsForButtonBar(org.eclipse.swt.widgets.Composite)
				 */
				@Override
				protected void createButtonsForButtonBar(Composite parent) {
			        Button[] buttons= new Button[2];
					buttons[0]= createButton(parent, restoreId, restoreButtonLabel, false);
			        buttons[1]= createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
			        setButtons(buttons);
				}
	        };
	        int returnValue= dialog.open();
	        if (restoreId == returnValue || settingsId == returnValue) {
	        	if (restoreId == returnValue) {
	        		IPreferenceStore store= JavaPlugin.getDefault().getPreferenceStore();
	        		store.setToDefault(PreferenceConstants.CODEASSIST_CATEGORY_ORDER);
	        		store.setToDefault(PreferenceConstants.CODEASSIST_EXCLUDED_CATEGORIES);
	        	}
				if (settingsId == returnValue) {
					PreferencesUtil.createPreferenceDialogOn(shell, "org.eclipse.jdt.ui.preferences.CodeAssistPreferenceAdvanced", null, null).open(); //$NON-NLS-1$
				}
	        	fComputerRegistry.reload();
	        	return true;
	        }
		}
		return false;
	}

	private List<CompletionProposalCategory> getSeparateCategories() {
		ArrayList<CompletionProposalCategory> sorted= new ArrayList<>();
		for (CompletionProposalCategory category : fCategories) {
			if (checkSeparateEnablement(category)) {
				sorted.add(category);
			}
		}
		Collections.sort(sorted, ORDER_COMPARATOR);
		return sorted;
	}

	/**
	 * Determine whether the category is enabled for separate use.
	 *
	 * @param category the category to check
	 * @return <code>true</code> if this category is enabled for separate use, <code>false</code>
	 *         otherwise
	 * @since 3.8
	 */
	protected boolean checkSeparateEnablement(CompletionProposalCategory category) {
		return category.isSeparateCommand() && category.hasComputers(fPartition);
	}

	private String createEmptyMessage() {
		return Messages.format(JavaTextMessages.ContentAssistProcessor_empty_message, new String[]{getCategoryLabel(fRepetition)});
	}

	private String createIterationMessage() {
		return Messages.format(JavaTextMessages.ContentAssistProcessor_toggle_affordance_update_message, new String[]{ getCategoryLabel(fRepetition), fIterationGesture, getCategoryLabel(fRepetition + 1) });
	}

	private String getCategoryLabel(int repetition) {
		int iteration= repetition % fCategoryIteration.size();
		if (iteration == 0) {
			return JavaTextMessages.ContentAssistProcessor_defaultProposalCategory;
		}
		return toString(fCategoryIteration.get(iteration).get(0));
	}

	private String toString(CompletionProposalCategory category) {
		return category.getDisplayName();
	}

	private String getIterationGesture() {
		TriggerSequence binding= getIterationBinding();
		return binding != null ?
				  Messages.format(JavaTextMessages.ContentAssistProcessor_toggle_affordance_press_gesture, new Object[] { binding.format() })
				: JavaTextMessages.ContentAssistProcessor_toggle_affordance_click_gesture;
	}

	private KeySequence getIterationBinding() {
	    final IBindingService bindingSvc= PlatformUI.getWorkbench().getAdapter(IBindingService.class);
		TriggerSequence binding= bindingSvc.getBestActiveBindingFor(ITextEditorActionDefinitionIds.CONTENT_ASSIST_PROPOSALS);
		if (binding instanceof KeySequence) {
			return (KeySequence) binding;
		}
		return null;
    }

	/**
	 * Sets the current proposal sorter into the content assistant.
	 *
	 * @since 3.8
	 * @see ProposalSorterRegistry#getCurrentSorter() the sorter used if <code>true</code>
	 */
	private void setContentAssistSorter() {
		ProposalSorterHandle currentSorter= ProposalSorterRegistry.getDefault().getCurrentSorter();
		try {
			fAssistant.setSorter(currentSorter.getSorter());
		} catch (InvalidRegistryObjectException x) {
			JavaPlugin.log(currentSorter.createExceptionStatus(x));
		} catch (CoreException x) {
			JavaPlugin.log(currentSorter.createExceptionStatus(x));
		} catch (RuntimeException x) {
			JavaPlugin.log(currentSorter.createExceptionStatus(x));
		}
	}

	/**
	 * Returns the proposal selected in the proposal selector.
	 *
	 * @return the selected proposal or <code>null</code> if none
	 * @since 3.12
	 */
	public ICompletionProposal getSelectedProposal() {
		return fSelectedProposal;
	}

}
