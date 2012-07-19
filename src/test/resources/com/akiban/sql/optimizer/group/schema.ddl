CREATE TABLE parent(id INT NOT NULL, PRIMARY KEY(id), name VARCHAR(256) NOT NULL, UNIQUE(name), state CHAR(2));
CREATE TABLE child(id INT NOT NULL, PRIMARY KEY(id), pid INT, GROUPING FOREIGN KEY(pid) REFERENCES parent(id), name VARCHAR(256) NOT NULL);
CREATE VIEW names(pname,cname) AS SELECT parent.name,child.name FROM parent,child WHERE parent.id = child.pid;
