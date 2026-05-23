grammar Cypher;

/*
 * DataWave read-only Cypher subset grammar (M0).
 *
 * Covers the v1 planner's accepted surface: MATCH / OPTIONAL MATCH (with
 * node, relationship, and bounded variable-length patterns), WHERE, WITH,
 * RETURN, ORDER BY, SKIP, LIMIT, DISTINCT, and expressions sufficient for
 * filtering and projection (comparison, boolean, arithmetic, property
 * access, function calls, parameters, literals).
 *
 * Deliberately omitted (rejected at parse time with a clean error):
 *   CREATE, MERGE, SET, DELETE, REMOVE, FOREACH, LOAD CSV, CALL,
 *   procedures / user-defined functions, unbounded variable-length
 *   patterns `[*]`, path quantifiers beyond `[*lo..hi]`.
 *
 * Grammar roughly tracks openCypher 9 / Cypher 25 for the productions it
 * accepts; new productions outside the read-only subset should be rejected
 * at parse time rather than silently accepted with no planner support.
 */

// ---------- Parser rules -------------------------------------------------

cypher
    : statement SEMI? EOF
    ;

statement
    : regularQuery
    ;

regularQuery
    : singleQuery (unionClause)*
    ;

unionClause
    : UNION ALL? singleQuery
    ;

singleQuery
    : readingClause* returnClause?
    ;

readingClause
    : matchClause
    | withClause
    ;

// ---------- MATCH --------------------------------------------------------

matchClause
    : OPTIONAL? MATCH pattern (COMMA pattern)* whereClause?
    ;

pattern
    : (symbolicName EQ)? patternElement
    ;

patternElement
    : nodePattern (relationshipPattern nodePattern)*
    | LPAREN patternElement RPAREN
    ;

nodePattern
    : LPAREN symbolicName? nodeLabels? properties? RPAREN
    ;

nodeLabels
    : (COLON symbolicName)+
    ;

relationshipPattern
    : leftArrow? dash relationshipDetail? dash rightArrow?
    ;

leftArrow : LT ;
rightArrow : GT ;
dash : MINUS ;

relationshipDetail
    : LBRACKET symbolicName? relationshipTypes? rangeLiteral? properties? RBRACKET
    ;

relationshipTypes
    : COLON symbolicName (PIPE COLON? symbolicName)*
    ;

rangeLiteral
    // v1 supports only the fully bounded form [*lo..hi]. Forms like [*],
    // [*n], [*..n], [*n..] are rejected at parse time with a clean
    // "mismatched input" error rather than accepted and later rejected
    // by the semantic analyzer.
    : STAR lower=INTEGER DOTDOT upper=INTEGER
    ;

properties
    : mapLiteral
    | parameter
    ;

// ---------- WHERE / WITH / RETURN ---------------------------------------

whereClause
    : WHERE expression
    ;

withClause
    : WITH DISTINCT? projectionItems orderByClause? skipClause? limitClause? whereClause?
    ;

returnClause
    : RETURN DISTINCT? projectionItems orderByClause? skipClause? limitClause?
    ;

projectionItems
    : STAR (COMMA projectionItem)*
    | projectionItem (COMMA projectionItem)*
    ;

projectionItem
    : expression (AS symbolicName)?
    ;

orderByClause
    : ORDER BY sortItem (COMMA sortItem)*
    ;

sortItem
    : expression (ASC | ASCENDING | DESC | DESCENDING)?
    ;

skipClause
    : SKIP_ expression
    ;

limitClause
    : LIMIT expression
    ;

// ---------- Expressions (precedence ladder) -----------------------------

expression
    : orExpr
    ;

orExpr
    : andExpr (OR andExpr)*
    ;

andExpr
    : notExpr (AND notExpr)*
    ;

notExpr
    : NOT notExpr
    | comparisonExpr
    ;

comparisonExpr
    : addExpr (comparisonOp addExpr)?
    ;

comparisonOp
    : EQ
    | NEQ
    | LT
    | GT
    | LTE
    | GTE
    ;

addExpr
    : mulExpr ((PLUS | MINUS) mulExpr)*
    ;

mulExpr
    : unaryExpr ((STAR | SLASH | PERCENT) unaryExpr)*
    ;

unaryExpr
    : MINUS unaryExpr
    | PLUS unaryExpr
    | propertyOrLabelExpr
    ;

propertyOrLabelExpr
    : atom (propertyLookup)* (COLON symbolicName)?
    ;

propertyLookup
    : DOT symbolicName
    ;

atom
    : literal
    | parameter
    | functionInvocation
    | variable
    | LPAREN expression RPAREN
    ;

functionInvocation
    : symbolicName LPAREN (DISTINCT)? (STAR | (expression (COMMA expression)*))? RPAREN
    ;

variable
    : symbolicName
    ;

parameter
    : DOLLAR (symbolicName | INTEGER)
    ;

literal
    : INTEGER
    | DECIMAL
    | STRING
    | TRUE
    | FALSE
    | NULL
    | listLiteral
    | mapLiteral
    ;

listLiteral
    : LBRACKET (expression (COMMA expression)*)? RBRACKET
    ;

mapLiteral
    : LBRACE (mapEntry (COMMA mapEntry)*)? RBRACE
    ;

mapEntry
    : symbolicName COLON expression
    ;

symbolicName
    : IDENTIFIER
    // M0 does NOT allow Cypher keywords to be reused as identifiers. A
    // parameter name that shadows a keyword (e.g. {@code $limit}) must be
    // renamed; this keeps the grammar unambiguous. Future milestones can
    // widen this rule once the downstream planner has clear rules for
    // disambiguation.
    ;

// ---------- Lexer rules --------------------------------------------------

// Keywords (case-insensitive).
MATCH      : [Mm][Aa][Tt][Cc][Hh] ;
OPTIONAL   : [Oo][Pp][Tt][Ii][Oo][Nn][Aa][Ll] ;
WHERE      : [Ww][Hh][Ee][Rr][Ee] ;
WITH       : [Ww][Ii][Tt][Hh] ;
RETURN     : [Rr][Ee][Tt][Uu][Rr][Nn] ;
DISTINCT   : [Dd][Ii][Ss][Tt][Ii][Nn][Cc][Tt] ;
ORDER      : [Oo][Rr][Dd][Ee][Rr] ;
BY         : [Bb][Yy] ;
ASC        : [Aa][Ss][Cc] ;
ASCENDING  : [Aa][Ss][Cc][Ee][Nn][Dd][Ii][Nn][Gg] ;
DESC       : [Dd][Ee][Ss][Cc] ;
DESCENDING : [Dd][Ee][Ss][Cc][Ee][Nn][Dd][Ii][Nn][Gg] ;
SKIP_      : [Ss][Kk][Ii][Pp] ;
LIMIT      : [Ll][Ii][Mm][Ii][Tt] ;
AS         : [Aa][Ss] ;
AND        : [Aa][Nn][Dd] ;
OR         : [Oo][Rr] ;
NOT        : [Nn][Oo][Tt] ;
TRUE       : [Tt][Rr][Uu][Ee] ;
FALSE      : [Ff][Aa][Ll][Ss][Ee] ;
NULL       : [Nn][Uu][Ll][Ll] ;
UNION      : [Uu][Nn][Ii][Oo][Nn] ;
ALL        : [Aa][Ll][Ll] ;

// Punctuation / operators.
LPAREN   : '(' ;
RPAREN   : ')' ;
LBRACKET : '[' ;
RBRACKET : ']' ;
LBRACE   : '{' ;
RBRACE   : '}' ;
COMMA    : ',' ;
COLON    : ':' ;
SEMI     : ';' ;
DOLLAR   : '$' ;
DOT      : '.' ;
DOTDOT   : '..' ;
PIPE     : '|' ;
PLUS     : '+' ;
MINUS    : '-' ;
STAR     : '*' ;
SLASH    : '/' ;
PERCENT  : '%' ;
EQ       : '=' ;
NEQ      : '<>' ;
LT       : '<' ;
GT       : '>' ;
LTE      : '<=' ;
GTE      : '>=' ;

// Literals.
INTEGER
    : [0-9]+
    ;

DECIMAL
    : [0-9]+ '.' [0-9]+ ([eE] [+\-]? [0-9]+)?
    | [0-9]+ [eE] [+\-]? [0-9]+
    ;

STRING
    : '\'' ( ~['\\\r\n] | '\\' . )* '\''
    | '"'  ( ~["\\\r\n] | '\\' . )* '"'
    ;

IDENTIFIER
    : [A-Za-z_][A-Za-z_0-9]*
    ;

// Whitespace and comments.
WS            : [ \t\r\n]+ -> skip ;
LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
