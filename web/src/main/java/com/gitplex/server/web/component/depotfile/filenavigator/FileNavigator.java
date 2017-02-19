package com.gitplex.server.web.component.depotfile.filenavigator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.form.OnChangeAjaxBehavior;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.extensions.markup.html.repeater.tree.ITreeProvider;
import org.apache.wicket.extensions.markup.html.repeater.tree.NestedTree;
import org.apache.wicket.extensions.markup.html.repeater.tree.theme.HumanTheme;
import org.apache.wicket.markup.ComponentTag;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.gitplex.server.git.BlobIdent;
import com.gitplex.server.security.SecurityUtils;
import com.gitplex.server.util.StringUtils;
import com.gitplex.server.web.component.BlobIcon;
import com.gitplex.server.web.component.DropdownLink;
import com.gitplex.server.web.component.PreventDefaultAjaxLink;
import com.gitplex.server.web.component.depotfile.blobview.BlobNameChangeCallback;
import com.gitplex.server.web.component.depotfile.blobview.BlobViewContext;
import com.gitplex.server.web.component.floating.AlignPlacement;
import com.gitplex.server.web.component.floating.FloatingPanel;
import com.gitplex.server.web.page.depot.file.DepotFilePage;
import com.gitplex.server.web.util.ajaxlistener.ConfirmLeaveListener;

@SuppressWarnings("serial")
public abstract class FileNavigator extends Panel {

	private final static String LAST_SEGMENT_ID = "lastSegment";
	
	private final BlobViewContext context;
	
	private final BlobNameChangeCallback callback;
	
	public FileNavigator(String id, BlobViewContext context, @Nullable BlobNameChangeCallback callback) {
		super(id);

		this.context = context;
		this.callback = callback;
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();

		add(new ListView<BlobIdent>("treeSegments", new LoadableDetachableModel<List<BlobIdent>>() {

			@Override
			protected List<BlobIdent> load() {
				BlobIdent file = context.getBlobIdent();
				List<BlobIdent> treeSegments = new ArrayList<>();
				treeSegments.add(new BlobIdent(file.revision, null, FileMode.TREE.getBits()));
				
				if (file.path != null) {
					List<String> segments = Splitter.on('/').omitEmptyStrings().splitToList(file.path);
					
					for (int i=0; i<segments.size(); i++) { 
						BlobIdent parent = treeSegments.get(treeSegments.size()-1);
						int treeMode = FileMode.TREE.getBits();
						if (i<segments.size()-1 || file.mode == treeMode) {
							String treePath = segments.get(i);
							if (parent.path != null)
								treePath = parent.path + "/" + treePath;
							treeSegments.add(new BlobIdent(file.revision, treePath, treeMode));
						}
					}
				}
				
				return treeSegments;
			}
			
		}) {

			@Override
			protected void populateItem(final ListItem<BlobIdent> item) {
				final BlobIdent blobIdent = item.getModelObject();
				AjaxLink<Void> link = new PreventDefaultAjaxLink<Void>("link") {

					@Override
					protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
						super.updateAjaxAttributes(attributes);
						attributes.getAjaxCallListeners().add(new ConfirmLeaveListener());
					}
					
					@Override
					public void onClick(AjaxRequestTarget target) {
						context.onSelect(target, blobIdent, null);
					}

					@Override
					protected void onComponentTag(ComponentTag tag) {
						super.onComponentTag(tag);
						DepotFilePage.State state = new DepotFilePage.State();
						state.blobIdent = blobIdent;
						PageParameters params = DepotFilePage.paramsOf(context.getDepot(), state);
						tag.put("href", urlFor(DepotFilePage.class, params));
					}
					
				};
				link.setEnabled(!context.getBlobIdent().isTree() || item.getIndex() != getViewSize()-1);
				
				if (blobIdent.path != null) {
					if (blobIdent.path.indexOf('/') != -1)
						link.add(new Label("label", StringUtils.substringAfterLast(blobIdent.path, "/")));
					else
						link.add(new Label("label", blobIdent.path));
				} else {
					link.add(new Label("label", context.getDepot().getName()));
				}
				
				item.add(link);
				
				item.add(new DropdownLink("subtreeDropdownTrigger", AlignPlacement.bottom(6)) {

					@Override
					protected void onInitialize(FloatingPanel dropdown) {
						super.onInitialize(dropdown);
						dropdown.add(AttributeAppender.append("class", "subtree-dropdown"));
					}

					@Override
					protected Component newContent(String id) {
						return new NestedTree<BlobIdent>(id, new ITreeProvider<BlobIdent>() {

							@Override
							public void detach() {
							}

							@Override
							public Iterator<? extends BlobIdent> getRoots() {
								Repository repository = context.getDepot().getRepository();
								try (RevWalk revWalk = new RevWalk(repository)) {
									RevTree revTree = revWalk.parseCommit(getCommitId()).getTree();
									TreeWalk treeWalk;
									if (blobIdent.path != null) {
										treeWalk = Preconditions.checkNotNull(TreeWalk.forPath(repository, blobIdent.path, revTree));
										treeWalk.enterSubtree();
									} else {
										treeWalk = new TreeWalk(repository);
										treeWalk.addTree(revTree);
									}
									treeWalk.setRecursive(false);
									
									List<BlobIdent> roots = new ArrayList<>();
									while (treeWalk.next()) 
										roots.add(new BlobIdent(context.getBlobIdent().revision, treeWalk.getPathString(), treeWalk.getRawMode(0)));
									Collections.sort(roots);
									return roots.iterator();
								} catch (IOException e) {
									throw new RuntimeException(e);
								}
							}

							@Override
							public boolean hasChildren(BlobIdent blobIdent) {
								return blobIdent.isTree();
							}

							@Override
							public Iterator<? extends BlobIdent> getChildren(BlobIdent blobIdent) {
								return FileNavigator.this.getChildren(blobIdent).iterator();
							}

							@Override
							public IModel<BlobIdent> model(BlobIdent blobIdent) {
								return Model.of(blobIdent);
							}
							
						}) {

							@Override
							protected void onInitialize() {
								super.onInitialize();
								add(new HumanTheme());				
							}

							@Override
							public void expand(BlobIdent blobIdent) {
								super.expand(blobIdent);
								
								List<BlobIdent> children = getChildren(blobIdent);
								if (children.size() == 1 && children.get(0).isTree()) 
									expand(children.get(0));
							}

							@Override
							protected Component newContentComponent(String id, final IModel<BlobIdent> model) {
								BlobIdent blobIdent = model.getObject();
								Fragment fragment = new Fragment(id, "treeNodeFrag", FileNavigator.this);

								fragment.add(new BlobIcon("icon", model));
								
								AjaxLink<Void> link = new PreventDefaultAjaxLink<Void>("link") {

									@Override
									protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
										super.updateAjaxAttributes(attributes);
										attributes.getAjaxCallListeners().add(new ConfirmLeaveListener());
									}
									
									@Override
									public void onClick(AjaxRequestTarget target) {
										context.onSelect(target, model.getObject(), null);
										close();
									}

									@Override
									protected void onComponentTag(ComponentTag tag) {
										super.onComponentTag(tag);
										
										DepotFilePage.State state = new DepotFilePage.State();
										state.blobIdent = model.getObject();
										PageParameters params = DepotFilePage.paramsOf(context.getDepot(), state);
										tag.put("href", urlFor(DepotFilePage.class, params));
									}
									
								};
								if (blobIdent.path.indexOf('/') != -1)
									link.add(new Label("label", StringUtils.substringAfterLast(blobIdent.path, "/")));
								else
									link.add(new Label("label", blobIdent.path));
								fragment.add(link);
								
								return fragment;
							}
							
						};		
					}
					
				});
			}
			
		});
		
		WebMarkupContainer lastSegment;
		BlobIdent file = context.getBlobIdent();
		if (callback != null) {
			lastSegment = new Fragment(LAST_SEGMENT_ID, "nameEditFrag", this);
			
			String name;
			if (file.isTree())
				name = "";
			else if (file.path.contains("/"))
				name = StringUtils.substringAfterLast(file.path, "/");
			else
				name = file.path;
			
			final TextField<String> nameInput = new TextField<String>("name", Model.of(name));
			lastSegment.add(nameInput);
			nameInput.add(new OnChangeAjaxBehavior() {

				@Override
				protected void onUpdate(AjaxRequestTarget target) {
					callback.onChange(target, nameInput.getInput());
				}
				
			});
			if (name.length() == 0)
				nameInput.add(AttributeAppender.append("class", "focusable"));
			lastSegment.add(AttributeAppender.append("class", "input"));
		} else if (file.isTree()) {
			lastSegment = new Fragment(LAST_SEGMENT_ID, "addFileFrag", this);
			lastSegment.add(new AjaxLink<Void>("addFile") {

				@Override
				protected void onConfigure() {
					super.onConfigure();
					String path = file.path;
					if (path != null && file.isTree())
						path += "/";
					setVisible(context.isOnBranch() && SecurityUtils.canModify(context.getDepot(), file.revision, path));
				}

				@Override
				protected void onComponentTag(ComponentTag tag) {
					super.onComponentTag(tag);
					tag.put("title", "Add new file");
				}
				
				@Override
				public void onClick(AjaxRequestTarget target) {
					onNewFile(target);
				}
				
			});
		} else {
			lastSegment = new Fragment(LAST_SEGMENT_ID, "blobNameFrag", this);
			
			String blobName = file.path;
			if (blobName.contains("/"))
				blobName = StringUtils.substringAfterLast(blobName, "/");
			lastSegment.add(new Label("label", blobName));
		} 
		add(lastSegment);
		
		setOutputMarkupId(true);
	}
	
	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(CssHeaderItem.forReference(new FileNavigatorResourceReference()));
		
		if (context.getBlobIdent().isTree()) {
			String script = String.format("$('#%s input[type=text]').focus();", getMarkupId());
			response.render(OnDomReadyHeaderItem.forScript(script));
		}
		
		String script = String.format("$('#%s form').submit(false);", getMarkupId());
		response.render(OnDomReadyHeaderItem.forScript(script));
	}

	private ObjectId getCommitId() {
		return context.getDepot().getObjectId(context.getBlobIdent().revision);
	}

	protected abstract void onNewFile(AjaxRequestTarget target);
	
	private List<BlobIdent> getChildren(BlobIdent blobIdent) {
		Repository repository = context.getDepot().getRepository();
		try (RevWalk revWalk = new RevWalk(repository)) {
			RevTree revTree = revWalk.parseCommit(getCommitId()).getTree();
			TreeWalk treeWalk = TreeWalk.forPath(repository, blobIdent.path, revTree);
			treeWalk.setRecursive(false);
			treeWalk.enterSubtree();
			
			List<BlobIdent> children = new ArrayList<>();
			while (treeWalk.next()) 
				children.add(new BlobIdent(context.getBlobIdent().revision, treeWalk.getPathString(), treeWalk.getRawMode(0)));
			Collections.sort(children);
			return children;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}