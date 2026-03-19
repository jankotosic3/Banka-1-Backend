-- liquibase formatted sql

-- changeset client-service:5
-- password for all seed clients is 'client123'
INSERT INTO clients (ime, prezime, datum_rodjenja, pol, email, broj_telefona, adresa, jmbg, password, role, version, deleted)
VALUES
    ('Marko',      'Markovic',  694310400000, 'M', 'marko.markovic@banka.com',      '+381641234567', 'Ulica 1, Beograd',    '0107991710025', '$argon2id$v=19$m=65536,t=3,p=1$cml4YnF1MGJOaG5md1cxOQ$kTOwNnDZmFymtQgsCUgpYFUJC9eV8wmpBCEldnS3XeE', 'CLIENT_BASIC', 0, false),
    ('Ana',        'Anic',      757382400000, 'Z', 'ana.anic@banka.com',            '+381652345678', 'Ulica 2, Novi Sad',   '0511994785014', '$argon2id$v=19$m=65536,t=3,p=1$cml4YnF1MGJOaG5md1cxOQ$kTOwNnDZmFymtQgsCUgpYFUJC9eV8wmpBCEldnS3XeE', 'CLIENT_BASIC', 0, false),
    ('Jovana',     'Jovanovic', 883612800000, 'Z', 'jovana.jovanovic@banka.com',    '+381663456789', 'Ulica 3, Nis',        '2207998785021', '$argon2id$v=19$m=65536,t=3,p=1$cml4YnF1MGJOaG5md1cxOQ$kTOwNnDZmFymtQgsCUgpYFUJC9eV8wmpBCEldnS3XeE', 'CLIENT_BASIC', 0, false),
    ('Stefan',     'Stefanovic',946684800000, 'M', 'stefan.stefanovic@banka.com',   '+381674567890', 'Ulica 4, Kragujevac', '1804000710034', '$argon2id$v=19$m=65536,t=3,p=1$cml4YnF1MGJOaG5md1cxOQ$kTOwNnDZmFymtQgsCUgpYFUJC9eV8wmpBCEldnS3XeE', 'CLIENT_BASIC', 0, false),
    ('Milica',     'Milic',     631152000000, 'Z', 'milica.milic@banka.com',        '+381685678901', 'Ulica 5, Subotica',   '1402990785022', '$argon2id$v=19$m=65536,t=3,p=1$cml4YnF1MGJOaG5md1cxOQ$kTOwNnDZmFymtQgsCUgpYFUJC9eV8wmpBCEldnS3XeE', 'CLIENT_BASIC', 0, false),
    ('Nikola',     'Nikolic',   724204800000, 'M', 'nikola.nikolic@banka.com',      '+381696789012', 'Ulica 6, Novi Sad',   '0309992710046', '$argon2id$v=19$m=65536,t=3,p=1$cml4YnF1MGJOaG5md1cxOQ$kTOwNnDZmFymtQgsCUgpYFUJC9eV8wmpBCEldnS3XeE', 'CLIENT_BASIC', 0, false),
    ('Jelena',     'Jelic',     565056000000, 'Z', 'jelena.jelic@banka.com',        '+381607890123', 'Ulica 7, Beograd',    '0112987785033', '$argon2id$v=19$m=65536,t=3,p=1$cml4YnF1MGJOaG5md1cxOQ$kTOwNnDZmFymtQgsCUgpYFUJC9eV8wmpBCEldnS3XeE', 'CLIENT_BASIC', 0, false),
    ('Aleksandar', 'Aleksic',   662688000000, 'M', 'aleksandar.aleksic@banka.com',  '+381618901234', 'Ulica 8, Nis',        '0302991710058', '$argon2id$v=19$m=65536,t=3,p=1$cml4YnF1MGJOaG5md1cxOQ$kTOwNnDZmFymtQgsCUgpYFUJC9eV8wmpBCEldnS3XeE', 'CLIENT_BASIC', 0, false);
