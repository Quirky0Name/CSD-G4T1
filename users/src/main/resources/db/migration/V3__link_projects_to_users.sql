-- A project can't outlive its owner: deleting an account deletes its projects with it.
-- Storage Management has already cleared their papers and drafts by then.
alter table projects
    add constraint fk_projects_owner foreign key (owner_id) references users (id) on delete cascade;
