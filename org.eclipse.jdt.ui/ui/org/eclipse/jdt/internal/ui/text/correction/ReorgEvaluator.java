
package org.eclipse.jdt.internal.ui.text.correction;

import java.util.ArrayList;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.compiler.IProblem;

import org.eclipse.jdt.internal.corext.refactoring.CompositeChange;
import org.eclipse.jdt.internal.corext.refactoring.base.Change;
import org.eclipse.jdt.internal.corext.refactoring.changes.CreatePackageChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.MoveCompilationUnitChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.RenameCompilationUnitChange;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.ui.viewsupport.JavaElementLabels;


public class ReorgEvaluator {
	
	public static void getWrongTypeNameProposals(ICompilationUnit cu, ProblemPosition problemPos, ArrayList proposals) throws CoreException {
		String[] args= problemPos.getArguments();
		if (args.length == 2) {
			// rename type
			Path path= new Path(args[0]);
			String newName= path.removeFileExtension().lastSegment();
			String label= "Rename type to '" + newName + "'";
			proposals.add(new ReplaceCorrectionProposal(cu, problemPos, label, newName));
			
			String newCUName= args[1] + ".java";
			final RenameCompilationUnitChange change= new RenameCompilationUnitChange(cu, newCUName);
			label= "Rename complation unit to '" + newCUName + "'";
			// rename cu
			proposals.add(new ChangeCorrectionProposal(label, problemPos) {
				protected Change getChange() throws CoreException {
					return change;
				}
			});
		}
	}
	
	public static void getWrongPackageDeclNameProposals(ICompilationUnit cu, ProblemPosition problemPos, ArrayList proposals) throws CoreException {
		String[] args= problemPos.getArguments();
		if (args.length == 1) {
			// correct pack decl
			proposals.add(new CorrectPackageDeclarationProposal(cu, problemPos));

			// move to pack
			IPackageFragment currPack= (IPackageFragment) cu.getParent();
			
			IPackageDeclaration[] packDecls= cu.getPackageDeclarations();
			String newPackName= packDecls.length > 0 ? packDecls[0].getElementName() : "";
				
			
			IPackageFragmentRoot root= JavaModelUtil.getPackageFragmentRoot(cu);
			IPackageFragment newPack= root.getPackageFragment(newPackName);

			String label;
			if (newPack.isDefaultPackage()) {
				label= "Move '" + cu.getElementName() + "' to the default package";
			} else {
				label= "Move '" + cu.getElementName() + "' to package '" + JavaElementLabels.getElementLabel(newPack, 0) + "'";
			}

			
			final CompositeChange composite= new CompositeChange(label);
			composite.add(new CreatePackageChange(newPack));
			composite.add(new MoveCompilationUnitChange(cu, newPack));

			proposals.add(new ChangeCorrectionProposal(label, problemPos) {
				protected Change getChange() throws CoreException {
					return composite;
				}
			});
		}
	}	
	

}
