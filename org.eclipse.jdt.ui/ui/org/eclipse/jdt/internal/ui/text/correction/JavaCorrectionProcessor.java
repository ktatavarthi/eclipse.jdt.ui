/*******************************************************************************
 * Copyright (c) 2000, 2012 IBM Corporation and others.
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

package org.eclipse.jdt.internal.ui.text.correction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.SafeRunner;
import org.eclipse.core.runtime.Status;

import org.eclipse.core.resources.IMarker;

import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.quickassist.IQuickAssistInvocationContext;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IMarkerHelpRegistry;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.keys.IBindingService;

import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.ui.texteditor.SimpleMarkerAnnotation;

import org.eclipse.ltk.core.refactoring.NullChange;

import org.eclipse.jdt.core.ICompilationUnit;

import org.eclipse.jdt.internal.corext.util.Messages;

import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.text.java.CompletionProposalComparator;
import org.eclipse.jdt.ui.text.java.IInvocationContext;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jdt.ui.text.java.IQuickAssistProcessor;
import org.eclipse.jdt.ui.text.java.IQuickFixProcessor;
import org.eclipse.jdt.ui.text.java.correction.ChangeCorrectionProposal;

import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.javaeditor.IJavaAnnotation;
import org.eclipse.jdt.internal.ui.text.correction.proposals.MarkerResolutionProposal;


public class JavaCorrectionProcessor implements org.eclipse.jface.text.quickassist.IQuickAssistProcessor {

	private static final String QUICKFIX_PROCESSOR_CONTRIBUTION_ID= "quickFixProcessors"; //$NON-NLS-1$
	private static final String QUICKASSIST_PROCESSOR_CONTRIBUTION_ID= "quickAssistProcessors"; //$NON-NLS-1$

	private static ContributedProcessorDescriptor[] fgContributedAssistProcessors= null;
	private static ContributedProcessorDescriptor[] fgContributedCorrectionProcessors= null;

	private static ContributedProcessorDescriptor[] getProcessorDescriptors(String contributionId, boolean testMarkerTypes) {
		IConfigurationElement[] elements= Platform.getExtensionRegistry().getConfigurationElementsFor(JavaUI.ID_PLUGIN, contributionId);
		ArrayList<ContributedProcessorDescriptor> res= new ArrayList<>(elements.length);

		for (IConfigurationElement element : elements) {
			ContributedProcessorDescriptor desc= new ContributedProcessorDescriptor(element, testMarkerTypes);
			IStatus status= desc.checkSyntax();
			if (status.isOK()) {
				res.add(desc);
			} else {
				JavaPlugin.log(status);
			}
		}
		return res.toArray(new ContributedProcessorDescriptor[res.size()]);
	}

	private static ContributedProcessorDescriptor[] getCorrectionProcessors() {
		if (fgContributedCorrectionProcessors == null) {
			fgContributedCorrectionProcessors= getProcessorDescriptors(QUICKFIX_PROCESSOR_CONTRIBUTION_ID, true);
		}
		return fgContributedCorrectionProcessors;
	}

	private static ContributedProcessorDescriptor[] getAssistProcessors() {
		if (fgContributedAssistProcessors == null) {
			fgContributedAssistProcessors= getProcessorDescriptors(QUICKASSIST_PROCESSOR_CONTRIBUTION_ID, false);
		}
		return fgContributedAssistProcessors;
	}

	public static boolean hasCorrections(ICompilationUnit cu, int problemId, String markerType) {
		SafeHasCorrections collector= new SafeHasCorrections(cu, problemId);
		for (ContributedProcessorDescriptor processor : getCorrectionProcessors()) {
			if (processor.canHandleMarkerType(markerType)) {
				collector.process(processor);
				if (collector.hasCorrections()) {
					return true;
				}
			}
		}
		return false;
	}

	public static boolean isQuickFixableType(Annotation annotation) {
		return (annotation instanceof IJavaAnnotation || annotation instanceof SimpleMarkerAnnotation) && !annotation.isMarkedDeleted();
	}


	public static boolean hasCorrections(Annotation annotation) {
		if (annotation instanceof IJavaAnnotation) {
			IJavaAnnotation javaAnnotation= (IJavaAnnotation) annotation;
			int problemId= javaAnnotation.getId();
			if (problemId != -1) {
				ICompilationUnit cu= javaAnnotation.getCompilationUnit();
				if (cu != null) {
					return hasCorrections(cu, problemId, javaAnnotation.getMarkerType());
				}
			}
		}
		if (annotation instanceof SimpleMarkerAnnotation) {
			return hasCorrections(((SimpleMarkerAnnotation) annotation).getMarker());
		}
		return false;
	}

	private static boolean hasCorrections(IMarker marker) {
		if (marker == null || !marker.exists())
			return false;

		IMarkerHelpRegistry registry= IDE.getMarkerHelpRegistry();
		return registry != null && registry.hasResolutions(marker);
	}

	public static boolean hasAssists(IInvocationContext context) {
		SafeHasAssist collector= new SafeHasAssist(context);

		for (ContributedProcessorDescriptor processor :  getAssistProcessors()) {
			collector.process(processor);
			if (collector.hasAssists()) {
				return true;
			}
		}
		return false;
	}

	private JavaCorrectionAssistant fAssistant;
	private String fErrorMessage;

	/*
	 * Constructor for JavaCorrectionProcessor.
	 */
	public JavaCorrectionProcessor(JavaCorrectionAssistant assistant) {
		fAssistant= assistant;
		fAssistant.addCompletionListener(new ICompletionListener() {

			@Override
			public void assistSessionEnded(ContentAssistEvent event) {
				fAssistant.setStatusLineVisible(false);
			}

			@Override
			public void assistSessionStarted(ContentAssistEvent event) {
				fAssistant.setStatusLineVisible(true);
				fAssistant.setStatusMessage(getJumpHintStatusLineMessage());
			}

			@Override
			public void selectionChanged(ICompletionProposal proposal, boolean smartToggle) {
				if (proposal instanceof IStatusLineProposal) {
					IStatusLineProposal statusLineProposal= (IStatusLineProposal)proposal;
					String message= statusLineProposal.getStatusMessage();
					if (message != null) {
						fAssistant.setStatusMessage(message);
						return;
					}
				}
				fAssistant.setStatusMessage(getJumpHintStatusLineMessage());
			}

			private String getJumpHintStatusLineMessage() {
				if (fAssistant.isUpdatedOffset()) {
					String key= getQuickAssistBinding();
					if (key == null)
						return CorrectionMessages.JavaCorrectionProcessor_go_to_original_using_menu;
					else
						return Messages.format(CorrectionMessages.JavaCorrectionProcessor_go_to_original_using_key, key);
				} else if (fAssistant.isProblemLocationAvailable()) {
					String key= getQuickAssistBinding();
					if (key == null)
						return CorrectionMessages.JavaCorrectionProcessor_go_to_closest_using_menu;
					else
						return Messages.format(CorrectionMessages.JavaCorrectionProcessor_go_to_closest_using_key, key);
				} else
					return ""; //$NON-NLS-1$
			}

			private String getQuickAssistBinding() {
				final IBindingService bindingSvc= PlatformUI.getWorkbench().getAdapter(IBindingService.class);
				return bindingSvc.getBestActiveBindingFormattedFor(ITextEditorActionDefinitionIds.QUICK_ASSIST);
			}
		});
	}

	/*
	 * @see IContentAssistProcessor#computeCompletionProposals(ITextViewer, int)
	 */
	@Override
	public ICompletionProposal[] computeQuickAssistProposals(IQuickAssistInvocationContext quickAssistContext) {
		ISourceViewer viewer= quickAssistContext.getSourceViewer();
		int documentOffset= quickAssistContext.getOffset();

		IEditorPart part= fAssistant.getEditor();

		ICompilationUnit cu= JavaUI.getWorkingCopyManager().getWorkingCopy(part.getEditorInput());
		IAnnotationModel model= JavaUI.getDocumentProvider().getAnnotationModel(part.getEditorInput());

		AssistContext context= null;
		if (cu != null) {
			int length= viewer != null ? viewer.getSelectedRange().y : 0;
			context= new AssistContext(cu, viewer, part, documentOffset, length);
		}

		Annotation[] annotations= fAssistant.getAnnotationsAtOffset();

		fErrorMessage= null;

		ICompletionProposal[] res= null;
		if (model != null && context != null && annotations != null) {
			ArrayList<IJavaCompletionProposal> proposals= new ArrayList<>(10);
			IStatus status= collectProposals(context, model, annotations, true, !fAssistant.isUpdatedOffset(), proposals);
			res= proposals.toArray(new ICompletionProposal[proposals.size()]);
			if (!status.isOK()) {
				fErrorMessage= status.getMessage();
				JavaPlugin.log(status);
			}
		}

		if (res == null || res.length == 0) {
			return new ICompletionProposal[] { new ChangeCorrectionProposal(CorrectionMessages.NoCorrectionProposal_description, new NullChange(""), IProposalRelevance.NO_SUGGESSTIONS_AVAILABLE, null) }; //$NON-NLS-1$
		}
		if (res.length > 1) {
			Arrays.sort(res, new CompletionProposalComparator());
		}
		return res;
	}

	public static IStatus collectProposals(IInvocationContext context, IAnnotationModel model, Annotation[] annotations, boolean addQuickFixes, boolean addQuickAssists, Collection<IJavaCompletionProposal> proposals) {
		ArrayList<ProblemLocation> problems= new ArrayList<>();

		// collect problem locations and corrections from marker annotations
		for (Annotation curr : annotations) {
			ProblemLocation problemLocation= null;
			if (curr instanceof IJavaAnnotation) {
				problemLocation= getProblemLocation((IJavaAnnotation) curr, model);
				if (problemLocation != null) {
					problems.add(problemLocation);
				}
			}
			if (problemLocation == null && addQuickFixes && curr instanceof SimpleMarkerAnnotation) {
				collectMarkerProposals((SimpleMarkerAnnotation) curr, proposals);
			}
		}
		MultiStatus resStatus= null;

		IProblemLocationCore[] problemLocations= problems.toArray(new IProblemLocationCore[problems.size()]);
		if (addQuickFixes) {
			IStatus status= collectCorrections(context, problemLocations, proposals);
			if (!status.isOK()) {
				resStatus= new MultiStatus(JavaUI.ID_PLUGIN, IStatus.ERROR, CorrectionMessages.JavaCorrectionProcessor_error_quickfix_message, null);
				resStatus.add(status);
			}
		}
		if (addQuickAssists) {
			IStatus status= collectAssists(context, problemLocations, proposals);
			if (!status.isOK()) {
				if (resStatus == null) {
					resStatus= new MultiStatus(JavaUI.ID_PLUGIN, IStatus.ERROR, CorrectionMessages.JavaCorrectionProcessor_error_quickassist_message, null);
				}
				resStatus.add(status);
			}
		}
		if (resStatus != null) {
			return resStatus;
		}
		return Status.OK_STATUS;
	}

	private static ProblemLocation getProblemLocation(IJavaAnnotation javaAnnotation, IAnnotationModel model) {
		int problemId= javaAnnotation.getId();
		if (problemId != -1) {
			Position pos= model.getPosition((Annotation) javaAnnotation);
			if (pos != null) {
				return new ProblemLocation(pos.getOffset(), pos.getLength(), javaAnnotation); // java problems all handled by the quick assist processors
			}
		}
		return null;
	}

	private static void collectMarkerProposals(SimpleMarkerAnnotation annotation, Collection<IJavaCompletionProposal> proposals) {
		IMarker marker= annotation.getMarker();
		for (IMarkerResolution resolution : IDE.getMarkerHelpRegistry().getResolutions(marker)) {
			proposals.add(new MarkerResolutionProposal(resolution, marker));
		}
	}

	private static abstract class SafeCorrectionProcessorAccess implements ISafeRunnable {
		private MultiStatus fMulti= null;
		private ContributedProcessorDescriptor fDescriptor;

		public void process(ContributedProcessorDescriptor[] desc) {
			for (ContributedProcessorDescriptor d : desc) {
				fDescriptor= d;
				SafeRunner.run(this);
			}
		}

		public void process(ContributedProcessorDescriptor desc) {
			fDescriptor= desc;
			SafeRunner.run(this);
		}

		@Override
		public void run() throws Exception {
			safeRun(fDescriptor);
		}

		protected abstract void safeRun(ContributedProcessorDescriptor processor) throws Exception;

		@Override
		public void handleException(Throwable exception) {
			if (fMulti == null) {
				fMulti= new MultiStatus(JavaUI.ID_PLUGIN, IStatus.OK, CorrectionMessages.JavaCorrectionProcessor_error_status, null);
			}
			fMulti.merge(new Status(IStatus.ERROR, JavaUI.ID_PLUGIN, IStatus.ERROR, CorrectionMessages.JavaCorrectionProcessor_error_status, exception));
		}

		public IStatus getStatus() {
			if (fMulti == null) {
				return Status.OK_STATUS;
			}
			return fMulti;
		}

	}

	private static class SafeCorrectionCollector extends SafeCorrectionProcessorAccess {
		private final IInvocationContext fContext;
		private final Collection<IJavaCompletionProposal> fProposals;
		private IProblemLocationCore[] fLocations;

		public SafeCorrectionCollector(IInvocationContext context, Collection<IJavaCompletionProposal> proposals) {
			fContext= context;
			fProposals= proposals;
		}

		public void setProblemLocations(IProblemLocationCore[] locations) {
			fLocations= locations;
		}

		@Override
		public void safeRun(ContributedProcessorDescriptor desc) throws Exception {
			IQuickFixProcessor curr= (IQuickFixProcessor) desc.getProcessor(fContext.getCompilationUnit(), IQuickFixProcessor.class);
			if (curr != null) {
				List<ProblemLocation> wrapped = Arrays.asList(fLocations).stream().map(x -> new ProblemLocation(x)).collect(Collectors.toList());
				ProblemLocation[] asArr = wrapped.toArray(new ProblemLocation[wrapped.size()]);
				IJavaCompletionProposal[] res= curr.getCorrections(fContext, asArr);
				if (res != null) {
					fProposals.addAll(Arrays.asList(res));
				}
			}
		}
	}

	private static class SafeAssistCollector extends SafeCorrectionProcessorAccess {
		private final IInvocationContext fContext;
		private final IProblemLocationCore[] fLocations;
		private final Collection<IJavaCompletionProposal> fProposals;

		public SafeAssistCollector(IInvocationContext context, IProblemLocationCore[] locations, Collection<IJavaCompletionProposal> proposals) {
			fContext= context;
			fLocations= locations;
			fProposals= proposals;
		}

		@Override
		public void safeRun(ContributedProcessorDescriptor desc) throws Exception {
			IQuickAssistProcessor curr= (IQuickAssistProcessor) desc.getProcessor(fContext.getCompilationUnit(), IQuickAssistProcessor.class);
			if (curr != null) {
				List<ProblemLocation> wrapped = Arrays.asList(fLocations).stream().map(x -> new ProblemLocation(x)).collect(Collectors.toList());
				ProblemLocation[] asArr = wrapped.toArray(new ProblemLocation[wrapped.size()]);
				IJavaCompletionProposal[] res= curr.getAssists(fContext, asArr);
				if (res != null) {
					fProposals.addAll(Arrays.asList(res));
				}
			}
		}
	}

	private static class SafeHasAssist extends SafeCorrectionProcessorAccess {
		private final IInvocationContext fContext;
		private boolean fHasAssists;

		public SafeHasAssist(IInvocationContext context) {
			fContext= context;
			fHasAssists= false;
		}

		public boolean hasAssists() {
			return fHasAssists;
		}

		@Override
		public void safeRun(ContributedProcessorDescriptor desc) throws Exception {
			IQuickAssistProcessor processor= (IQuickAssistProcessor) desc.getProcessor(fContext.getCompilationUnit(), IQuickAssistProcessor.class);
			if (processor != null && processor.hasAssists(fContext)) {
				fHasAssists= true;
			}
		}
	}

	private static class SafeHasCorrections extends SafeCorrectionProcessorAccess {
		private final ICompilationUnit fCu;
		private final int fProblemId;
		private boolean fHasCorrections;

		public SafeHasCorrections(ICompilationUnit cu, int problemId) {
			fCu= cu;
			fProblemId= problemId;
			fHasCorrections= false;
		}

		public boolean hasCorrections() {
			return fHasCorrections;
		}

		@Override
		public void safeRun(ContributedProcessorDescriptor desc) throws Exception {
			IQuickFixProcessor processor= (IQuickFixProcessor) desc.getProcessor(fCu, IQuickFixProcessor.class);
			if (processor != null && processor.hasCorrections(fCu, fProblemId)) {
				fHasCorrections= true;
			}
		}
	}


	public static IStatus collectCorrections(IInvocationContext context, IProblemLocationCore[] locations, Collection<IJavaCompletionProposal> proposals) {
		SafeCorrectionCollector collector= new SafeCorrectionCollector(context, proposals);
		for (ContributedProcessorDescriptor curr : getCorrectionProcessors()) {
			IProblemLocationCore[] handled= getHandledProblems(locations, curr);
			if (handled != null) {
				collector.setProblemLocations(handled);
				collector.process(curr);
			}
		}
		return collector.getStatus();
	}

	private static IProblemLocationCore[] getHandledProblems(IProblemLocationCore[] locations, ContributedProcessorDescriptor processor) {
		// implementation tries to avoid creating a new array
		boolean allHandled= true;
		ArrayList<IProblemLocationCore> res= null;
		for (int i= 0; i < locations.length; i++) {
			IProblemLocationCore curr= locations[i];
			if (processor.canHandleMarkerType(curr.getMarkerType())) {
				if (!allHandled) { // first handled problem
					if (res == null) {
						res= new ArrayList<>(locations.length - i);
					}
					res.add(curr);
				}
			} else if (allHandled) {
				if (i > 0) { // first non handled problem
					res= new ArrayList<>(locations.length - i);
					for (int k= 0; k < i; k++) {
						res.add(locations[k]);
					}
				}
				allHandled= false;
			}
		}
		if (allHandled) {
			return locations;
		}
		if (res == null) {
			return null;
		}
		return res.toArray(new IProblemLocationCore[res.size()]);
	}

	public static IStatus collectAssists(IInvocationContext context, IProblemLocationCore[] locations, Collection<IJavaCompletionProposal> proposals) {
		ContributedProcessorDescriptor[] processors= getAssistProcessors();
		SafeAssistCollector collector= new SafeAssistCollector(context, locations, proposals);
		collector.process(processors);

		return collector.getStatus();
	}

	/*
	 * @see IContentAssistProcessor#getErrorMessage()
	 */
	@Override
	public String getErrorMessage() {
		return fErrorMessage;
	}

	/*
	 * @see org.eclipse.jface.text.quickassist.IQuickAssistProcessor#canFix(org.eclipse.jface.text.source.Annotation)
	 * @since 3.2
	 */
	@Override
	public boolean canFix(Annotation annotation) {
		return hasCorrections(annotation);
	}

	/*
	 * @see org.eclipse.jface.text.quickassist.IQuickAssistProcessor#canAssist(org.eclipse.jface.text.quickassist.IQuickAssistInvocationContext)
	 * @since 3.2
	 */
	@Override
	public boolean canAssist(IQuickAssistInvocationContext invocationContext) {
		if (invocationContext instanceof IInvocationContext)
			return hasAssists((IInvocationContext)invocationContext);
		return false;
	}

}
