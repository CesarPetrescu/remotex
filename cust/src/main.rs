use std::collections::HashMap;
use std::env;
use std::fmt;
use std::fs;
use std::process;

fn main() {
    let args: Vec<String> = env::args().collect();
    let source = match args.get(1) {
        Some(path) => match fs::read_to_string(path) {
            Ok(contents) => contents,
            Err(err) => {
                eprintln!("failed to read {path}: {err}");
                process::exit(1);
            }
        },
        None => DEMO.to_string(),
    };

    if let Err(err) = run(&source) {
        eprintln!("{err}");
        process::exit(1);
    }
}

const DEMO: &str = r#"
int x = 0;
int total = 0;

while (x < 5) {
    total = total + x;
    x = x + 1;
}

if (total == 10) {
    print(total);
} else {
    print(0);
}
"#;

fn run(source: &str) -> Result<ExecOutcome, CustError> {
    let tokens = Lexer::new(source).tokenize()?;
    let program = Parser::new(tokens).parse_program()?;
    let mut interpreter = Interpreter::new();
    interpreter.exec_program(&program)
}

#[derive(Debug, Clone, PartialEq)]
enum TokenKind {
    Int(i64),
    Ident(String),
    Keyword(Keyword),
    Plus,
    Minus,
    Star,
    Slash,
    Percent,
    Assign,
    Eq,
    NotEq,
    Less,
    LessEq,
    Greater,
    GreaterEq,
    AndAnd,
    OrOr,
    Bang,
    LParen,
    RParen,
    LBrace,
    RBrace,
    Semicolon,
    Comma,
    Eof,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Keyword {
    Int,
    If,
    Else,
    While,
    Return,
}

#[derive(Debug, Clone, PartialEq)]
struct Token {
    kind: TokenKind,
    line: usize,
    col: usize,
}

struct Lexer {
    chars: Vec<char>,
    pos: usize,
    line: usize,
    col: usize,
}

impl Lexer {
    fn new(source: &str) -> Self {
        Self {
            chars: source.chars().collect(),
            pos: 0,
            line: 1,
            col: 1,
        }
    }

    fn tokenize(mut self) -> Result<Vec<Token>, CustError> {
        let mut tokens = Vec::new();

        loop {
            self.skip_whitespace_and_comments()?;
            let line = self.line;
            let col = self.col;
            let Some(ch) = self.peek() else {
                tokens.push(Token {
                    kind: TokenKind::Eof,
                    line,
                    col,
                });
                return Ok(tokens);
            };

            let kind = match ch {
                '0'..='9' => self.read_number()?,
                'a'..='z' | 'A'..='Z' | '_' => self.read_identifier_or_keyword(),
                '+' => {
                    self.advance();
                    TokenKind::Plus
                }
                '-' => {
                    self.advance();
                    TokenKind::Minus
                }
                '*' => {
                    self.advance();
                    TokenKind::Star
                }
                '/' => {
                    self.advance();
                    TokenKind::Slash
                }
                '%' => {
                    self.advance();
                    TokenKind::Percent
                }
                '=' => {
                    self.advance();
                    if self.match_char('=') {
                        TokenKind::Eq
                    } else {
                        TokenKind::Assign
                    }
                }
                '!' => {
                    self.advance();
                    if self.match_char('=') {
                        TokenKind::NotEq
                    } else {
                        TokenKind::Bang
                    }
                }
                '<' => {
                    self.advance();
                    if self.match_char('=') {
                        TokenKind::LessEq
                    } else {
                        TokenKind::Less
                    }
                }
                '>' => {
                    self.advance();
                    if self.match_char('=') {
                        TokenKind::GreaterEq
                    } else {
                        TokenKind::Greater
                    }
                }
                '&' => {
                    self.advance();
                    if self.match_char('&') {
                        TokenKind::AndAnd
                    } else {
                        return Err(self.error_at(line, col, "expected '&' after '&'"));
                    }
                }
                '|' => {
                    self.advance();
                    if self.match_char('|') {
                        TokenKind::OrOr
                    } else {
                        return Err(self.error_at(line, col, "expected '|' after '|'"));
                    }
                }
                '(' => {
                    self.advance();
                    TokenKind::LParen
                }
                ')' => {
                    self.advance();
                    TokenKind::RParen
                }
                '{' => {
                    self.advance();
                    TokenKind::LBrace
                }
                '}' => {
                    self.advance();
                    TokenKind::RBrace
                }
                ';' => {
                    self.advance();
                    TokenKind::Semicolon
                }
                ',' => {
                    self.advance();
                    TokenKind::Comma
                }
                _ => {
                    return Err(self.error_at(
                        line,
                        col,
                        format!("unexpected character '{ch}'"),
                    ));
                }
            };

            tokens.push(Token { kind, line, col });
        }
    }

    fn skip_whitespace_and_comments(&mut self) -> Result<(), CustError> {
        loop {
            while matches!(self.peek(), Some(ch) if ch.is_whitespace()) {
                self.advance();
            }

            if self.peek() == Some('/') && self.peek_next() == Some('/') {
                while !matches!(self.peek(), None | Some('\n')) {
                    self.advance();
                }
                continue;
            }

            if self.peek() == Some('/') && self.peek_next() == Some('*') {
                let start_line = self.line;
                let start_col = self.col;
                self.advance();
                self.advance();
                while !(self.peek() == Some('*') && self.peek_next() == Some('/')) {
                    if self.peek().is_none() {
                        return Err(self.error_at(
                            start_line,
                            start_col,
                            "unterminated block comment",
                        ));
                    }
                    self.advance();
                }
                self.advance();
                self.advance();
                continue;
            }

            return Ok(());
        }
    }

    fn read_number(&mut self) -> Result<TokenKind, CustError> {
        let line = self.line;
        let col = self.col;
        let start = self.pos;
        while matches!(self.peek(), Some('0'..='9')) {
            self.advance();
        }
        let text: String = self.chars[start..self.pos].iter().collect();
        let value = text.parse::<i64>().map_err(|_| {
            self.error_at(line, col, format!("integer literal is out of range: {text}"))
        })?;
        Ok(TokenKind::Int(value))
    }

    fn read_identifier_or_keyword(&mut self) -> TokenKind {
        let start = self.pos;
        while matches!(self.peek(), Some(ch) if ch.is_ascii_alphanumeric() || ch == '_') {
            self.advance();
        }
        let text: String = self.chars[start..self.pos].iter().collect();
        match text.as_str() {
            "int" => TokenKind::Keyword(Keyword::Int),
            "if" => TokenKind::Keyword(Keyword::If),
            "else" => TokenKind::Keyword(Keyword::Else),
            "while" => TokenKind::Keyword(Keyword::While),
            "return" => TokenKind::Keyword(Keyword::Return),
            _ => TokenKind::Ident(text),
        }
    }

    fn peek(&self) -> Option<char> {
        self.chars.get(self.pos).copied()
    }

    fn peek_next(&self) -> Option<char> {
        self.chars.get(self.pos + 1).copied()
    }

    fn advance(&mut self) -> Option<char> {
        let ch = self.peek()?;
        self.pos += 1;
        if ch == '\n' {
            self.line += 1;
            self.col = 1;
        } else {
            self.col += 1;
        }
        Some(ch)
    }

    fn match_char(&mut self, expected: char) -> bool {
        if self.peek() == Some(expected) {
            self.advance();
            true
        } else {
            false
        }
    }

    fn error_at(&self, line: usize, col: usize, message: impl Into<String>) -> CustError {
        CustError::new(ErrorKind::Lex, line, col, message)
    }
}

#[derive(Debug, Clone, PartialEq)]
enum Stmt {
    VarDecl { name: String, init: Option<Expr> },
    Assign { name: String, value: Expr },
    Expr(Expr),
    Print(Vec<Expr>),
    Block(Vec<Stmt>),
    If {
        condition: Expr,
        then_branch: Box<Stmt>,
        else_branch: Option<Box<Stmt>>,
    },
    While { condition: Expr, body: Box<Stmt> },
    Return(Option<Expr>),
}

#[derive(Debug, Clone, PartialEq)]
enum Expr {
    Int(i64),
    Var(String),
    Unary { op: UnaryOp, expr: Box<Expr> },
    Binary {
        left: Box<Expr>,
        op: BinaryOp,
        right: Box<Expr>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum UnaryOp {
    Neg,
    Not,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BinaryOp {
    Add,
    Sub,
    Mul,
    Div,
    Rem,
    Eq,
    NotEq,
    Less,
    LessEq,
    Greater,
    GreaterEq,
    And,
    Or,
}

struct Parser {
    tokens: Vec<Token>,
    pos: usize,
}

impl Parser {
    fn new(tokens: Vec<Token>) -> Self {
        Self { tokens, pos: 0 }
    }

    fn parse_program(&mut self) -> Result<Vec<Stmt>, CustError> {
        let mut statements = Vec::new();
        while !self.check(&TokenKind::Eof) {
            statements.push(self.parse_stmt()?);
        }
        Ok(statements)
    }

    fn parse_stmt(&mut self) -> Result<Stmt, CustError> {
        if self.match_keyword(Keyword::Int) {
            return self.parse_var_decl();
        }
        if self.match_keyword(Keyword::If) {
            return self.parse_if();
        }
        if self.match_keyword(Keyword::While) {
            return self.parse_while();
        }
        if self.match_keyword(Keyword::Return) {
            return self.parse_return();
        }
        if self.match_kind(&TokenKind::LBrace) {
            return self.parse_block();
        }
        if self.check_ident("print") && self.check_next(&TokenKind::LParen) {
            return self.parse_print();
        }
        if matches!(self.current().kind, TokenKind::Ident(_))
            && self.check_next(&TokenKind::Assign)
        {
            return self.parse_assign();
        }

        let expr = self.parse_expr()?;
        self.consume(&TokenKind::Semicolon, "expected ';' after expression")?;
        Ok(Stmt::Expr(expr))
    }

    fn parse_var_decl(&mut self) -> Result<Stmt, CustError> {
        let name = self.consume_ident("expected variable name after 'int'")?;
        let init = if self.match_kind(&TokenKind::Assign) {
            Some(self.parse_expr()?)
        } else {
            None
        };
        self.consume(&TokenKind::Semicolon, "expected ';' after declaration")?;
        Ok(Stmt::VarDecl { name, init })
    }

    fn parse_if(&mut self) -> Result<Stmt, CustError> {
        self.consume(&TokenKind::LParen, "expected '(' after 'if'")?;
        let condition = self.parse_expr()?;
        self.consume(&TokenKind::RParen, "expected ')' after if condition")?;
        let then_branch = Box::new(self.parse_stmt()?);
        let else_branch = if self.match_keyword(Keyword::Else) {
            Some(Box::new(self.parse_stmt()?))
        } else {
            None
        };
        Ok(Stmt::If {
            condition,
            then_branch,
            else_branch,
        })
    }

    fn parse_while(&mut self) -> Result<Stmt, CustError> {
        self.consume(&TokenKind::LParen, "expected '(' after 'while'")?;
        let condition = self.parse_expr()?;
        self.consume(&TokenKind::RParen, "expected ')' after while condition")?;
        let body = Box::new(self.parse_stmt()?);
        Ok(Stmt::While { condition, body })
    }

    fn parse_return(&mut self) -> Result<Stmt, CustError> {
        let value = if self.check(&TokenKind::Semicolon) {
            None
        } else {
            Some(self.parse_expr()?)
        };
        self.consume(&TokenKind::Semicolon, "expected ';' after return")?;
        Ok(Stmt::Return(value))
    }

    fn parse_block(&mut self) -> Result<Stmt, CustError> {
        let mut statements = Vec::new();
        while !self.check(&TokenKind::RBrace) && !self.check(&TokenKind::Eof) {
            statements.push(self.parse_stmt()?);
        }
        self.consume(&TokenKind::RBrace, "expected '}' after block")?;
        Ok(Stmt::Block(statements))
    }

    fn parse_print(&mut self) -> Result<Stmt, CustError> {
        self.consume_ident("expected 'print'")?;
        self.consume(&TokenKind::LParen, "expected '(' after 'print'")?;
        let mut args = Vec::new();
        if !self.check(&TokenKind::RParen) {
            loop {
                args.push(self.parse_expr()?);
                if !self.match_kind(&TokenKind::Comma) {
                    break;
                }
            }
        }
        self.consume(&TokenKind::RParen, "expected ')' after print arguments")?;
        self.consume(&TokenKind::Semicolon, "expected ';' after print call")?;
        Ok(Stmt::Print(args))
    }

    fn parse_assign(&mut self) -> Result<Stmt, CustError> {
        let name = self.consume_ident("expected assignment target")?;
        self.consume(&TokenKind::Assign, "expected '=' in assignment")?;
        let value = self.parse_expr()?;
        self.consume(&TokenKind::Semicolon, "expected ';' after assignment")?;
        Ok(Stmt::Assign { name, value })
    }

    fn parse_expr(&mut self) -> Result<Expr, CustError> {
        self.parse_or()
    }

    fn parse_or(&mut self) -> Result<Expr, CustError> {
        let mut expr = self.parse_and()?;
        while self.match_kind(&TokenKind::OrOr) {
            let right = self.parse_and()?;
            expr = Expr::Binary {
                left: Box::new(expr),
                op: BinaryOp::Or,
                right: Box::new(right),
            };
        }
        Ok(expr)
    }

    fn parse_and(&mut self) -> Result<Expr, CustError> {
        let mut expr = self.parse_equality()?;
        while self.match_kind(&TokenKind::AndAnd) {
            let right = self.parse_equality()?;
            expr = Expr::Binary {
                left: Box::new(expr),
                op: BinaryOp::And,
                right: Box::new(right),
            };
        }
        Ok(expr)
    }

    fn parse_equality(&mut self) -> Result<Expr, CustError> {
        let mut expr = self.parse_comparison()?;
        loop {
            let op = if self.match_kind(&TokenKind::Eq) {
                BinaryOp::Eq
            } else if self.match_kind(&TokenKind::NotEq) {
                BinaryOp::NotEq
            } else {
                break;
            };
            let right = self.parse_comparison()?;
            expr = Expr::Binary {
                left: Box::new(expr),
                op,
                right: Box::new(right),
            };
        }
        Ok(expr)
    }

    fn parse_comparison(&mut self) -> Result<Expr, CustError> {
        let mut expr = self.parse_term()?;
        loop {
            let op = if self.match_kind(&TokenKind::Less) {
                BinaryOp::Less
            } else if self.match_kind(&TokenKind::LessEq) {
                BinaryOp::LessEq
            } else if self.match_kind(&TokenKind::Greater) {
                BinaryOp::Greater
            } else if self.match_kind(&TokenKind::GreaterEq) {
                BinaryOp::GreaterEq
            } else {
                break;
            };
            let right = self.parse_term()?;
            expr = Expr::Binary {
                left: Box::new(expr),
                op,
                right: Box::new(right),
            };
        }
        Ok(expr)
    }

    fn parse_term(&mut self) -> Result<Expr, CustError> {
        let mut expr = self.parse_factor()?;
        loop {
            let op = if self.match_kind(&TokenKind::Plus) {
                BinaryOp::Add
            } else if self.match_kind(&TokenKind::Minus) {
                BinaryOp::Sub
            } else {
                break;
            };
            let right = self.parse_factor()?;
            expr = Expr::Binary {
                left: Box::new(expr),
                op,
                right: Box::new(right),
            };
        }
        Ok(expr)
    }

    fn parse_factor(&mut self) -> Result<Expr, CustError> {
        let mut expr = self.parse_unary()?;
        loop {
            let op = if self.match_kind(&TokenKind::Star) {
                BinaryOp::Mul
            } else if self.match_kind(&TokenKind::Slash) {
                BinaryOp::Div
            } else if self.match_kind(&TokenKind::Percent) {
                BinaryOp::Rem
            } else {
                break;
            };
            let right = self.parse_unary()?;
            expr = Expr::Binary {
                left: Box::new(expr),
                op,
                right: Box::new(right),
            };
        }
        Ok(expr)
    }

    fn parse_unary(&mut self) -> Result<Expr, CustError> {
        if self.match_kind(&TokenKind::Minus) {
            return Ok(Expr::Unary {
                op: UnaryOp::Neg,
                expr: Box::new(self.parse_unary()?),
            });
        }
        if self.match_kind(&TokenKind::Bang) {
            return Ok(Expr::Unary {
                op: UnaryOp::Not,
                expr: Box::new(self.parse_unary()?),
            });
        }
        self.parse_primary()
    }

    fn parse_primary(&mut self) -> Result<Expr, CustError> {
        let token = self.current().clone();
        match token.kind {
            TokenKind::Int(value) => {
                self.advance();
                Ok(Expr::Int(value))
            }
            TokenKind::Ident(name) => {
                self.advance();
                Ok(Expr::Var(name))
            }
            TokenKind::LParen => {
                self.advance();
                let expr = self.parse_expr()?;
                self.consume(&TokenKind::RParen, "expected ')' after expression")?;
                Ok(expr)
            }
            _ => Err(self.error_at_current("expected expression")),
        }
    }

    fn consume(&mut self, expected: &TokenKind, message: &str) -> Result<(), CustError> {
        if self.check(expected) {
            self.advance();
            Ok(())
        } else {
            Err(self.error_at_current(message))
        }
    }

    fn consume_ident(&mut self, message: &str) -> Result<String, CustError> {
        let token = self.current().clone();
        if let TokenKind::Ident(name) = token.kind {
            self.advance();
            Ok(name)
        } else {
            Err(self.error_at_current(message))
        }
    }

    fn match_keyword(&mut self, keyword: Keyword) -> bool {
        if self.check(&TokenKind::Keyword(keyword)) {
            self.advance();
            true
        } else {
            false
        }
    }

    fn match_kind(&mut self, kind: &TokenKind) -> bool {
        if self.check(kind) {
            self.advance();
            true
        } else {
            false
        }
    }

    fn check(&self, kind: &TokenKind) -> bool {
        self.current().kind == *kind
    }

    fn check_next(&self, kind: &TokenKind) -> bool {
        self.tokens
            .get(self.pos + 1)
            .map(|token| token.kind == *kind)
            .unwrap_or(false)
    }

    fn check_ident(&self, expected: &str) -> bool {
        matches!(&self.current().kind, TokenKind::Ident(name) if name == expected)
    }

    fn current(&self) -> &Token {
        self.tokens
            .get(self.pos)
            .expect("parser should always have an EOF token")
    }

    fn advance(&mut self) {
        if !self.check(&TokenKind::Eof) {
            self.pos += 1;
        }
    }

    fn error_at_current(&self, message: impl Into<String>) -> CustError {
        let token = self.current();
        CustError::new(ErrorKind::Parse, token.line, token.col, message)
    }
}

#[derive(Debug, Clone, PartialEq)]
struct ExecOutcome {
    output: Vec<i64>,
    return_value: Option<i64>,
}

struct Interpreter {
    scopes: Vec<HashMap<String, i64>>,
    output: Vec<i64>,
}

enum Control {
    Continue,
    Return(i64),
}

impl Interpreter {
    fn new() -> Self {
        Self {
            scopes: vec![HashMap::new()],
            output: Vec::new(),
        }
    }

    fn exec_program(&mut self, statements: &[Stmt]) -> Result<ExecOutcome, CustError> {
        let return_value = match self.exec_statements(statements)? {
            Control::Continue => None,
            Control::Return(value) => Some(value),
        };
        Ok(ExecOutcome {
            output: self.output.clone(),
            return_value,
        })
    }

    fn exec_statements(&mut self, statements: &[Stmt]) -> Result<Control, CustError> {
        for statement in statements {
            match self.exec_stmt(statement)? {
                Control::Continue => {}
                ret @ Control::Return(_) => return Ok(ret),
            }
        }
        Ok(Control::Continue)
    }

    fn exec_stmt(&mut self, statement: &Stmt) -> Result<Control, CustError> {
        match statement {
            Stmt::VarDecl { name, init } => {
                let value = match init {
                    Some(expr) => self.eval(expr)?,
                    None => 0,
                };
                self.declare(name, value)?;
                Ok(Control::Continue)
            }
            Stmt::Assign { name, value } => {
                let value = self.eval(value)?;
                self.assign(name, value)?;
                Ok(Control::Continue)
            }
            Stmt::Expr(expr) => {
                self.eval(expr)?;
                Ok(Control::Continue)
            }
            Stmt::Print(args) => {
                let mut values = Vec::with_capacity(args.len());
                for arg in args {
                    values.push(self.eval(arg)?);
                }
                for value in values {
                    println!("{value}");
                    self.output.push(value);
                }
                Ok(Control::Continue)
            }
            Stmt::Block(statements) => {
                self.scopes.push(HashMap::new());
                let result = self.exec_statements(statements);
                self.scopes.pop();
                result
            }
            Stmt::If {
                condition,
                then_branch,
                else_branch,
            } => {
                if truthy(self.eval(condition)?) {
                    self.exec_stmt(then_branch)
                } else if let Some(else_branch) = else_branch {
                    self.exec_stmt(else_branch)
                } else {
                    Ok(Control::Continue)
                }
            }
            Stmt::While { condition, body } => {
                while truthy(self.eval(condition)?) {
                    match self.exec_stmt(body)? {
                        Control::Continue => {}
                        ret @ Control::Return(_) => return Ok(ret),
                    }
                }
                Ok(Control::Continue)
            }
            Stmt::Return(value) => {
                let value = match value {
                    Some(expr) => self.eval(expr)?,
                    None => 0,
                };
                Ok(Control::Return(value))
            }
        }
    }

    fn eval(&mut self, expr: &Expr) -> Result<i64, CustError> {
        match expr {
            Expr::Int(value) => Ok(*value),
            Expr::Var(name) => self.lookup(name),
            Expr::Unary { op, expr } => {
                let value = self.eval(expr)?;
                match op {
                    UnaryOp::Neg => Ok(-value),
                    UnaryOp::Not => Ok(if truthy(value) { 0 } else { 1 }),
                }
            }
            Expr::Binary { left, op, right } => match op {
                BinaryOp::And => {
                    let left = self.eval(left)?;
                    if !truthy(left) {
                        Ok(0)
                    } else {
                        Ok(if truthy(self.eval(right)?) { 1 } else { 0 })
                    }
                }
                BinaryOp::Or => {
                    let left = self.eval(left)?;
                    if truthy(left) {
                        Ok(1)
                    } else {
                        Ok(if truthy(self.eval(right)?) { 1 } else { 0 })
                    }
                }
                _ => {
                    let left = self.eval(left)?;
                    let right = self.eval(right)?;
                    self.eval_binary(left, *op, right)
                }
            },
        }
    }

    fn eval_binary(&self, left: i64, op: BinaryOp, right: i64) -> Result<i64, CustError> {
        match op {
            BinaryOp::Add => Ok(left + right),
            BinaryOp::Sub => Ok(left - right),
            BinaryOp::Mul => Ok(left * right),
            BinaryOp::Div => {
                if right == 0 {
                    Err(runtime_error("division by zero"))
                } else {
                    Ok(left / right)
                }
            }
            BinaryOp::Rem => {
                if right == 0 {
                    Err(runtime_error("remainder by zero"))
                } else {
                    Ok(left % right)
                }
            }
            BinaryOp::Eq => Ok((left == right) as i64),
            BinaryOp::NotEq => Ok((left != right) as i64),
            BinaryOp::Less => Ok((left < right) as i64),
            BinaryOp::LessEq => Ok((left <= right) as i64),
            BinaryOp::Greater => Ok((left > right) as i64),
            BinaryOp::GreaterEq => Ok((left >= right) as i64),
            BinaryOp::And | BinaryOp::Or => unreachable!("logical ops short-circuit in eval"),
        }
    }

    fn declare(&mut self, name: &str, value: i64) -> Result<(), CustError> {
        let scope = self.scopes.last_mut().expect("interpreter always has a scope");
        if scope.contains_key(name) {
            return Err(runtime_error(format!(
                "variable '{name}' is already declared in this scope"
            )));
        }
        scope.insert(name.to_string(), value);
        Ok(())
    }

    fn assign(&mut self, name: &str, value: i64) -> Result<(), CustError> {
        for scope in self.scopes.iter_mut().rev() {
            if scope.contains_key(name) {
                scope.insert(name.to_string(), value);
                return Ok(());
            }
        }
        Err(runtime_error(format!("undefined variable '{name}'")))
    }

    fn lookup(&self, name: &str) -> Result<i64, CustError> {
        for scope in self.scopes.iter().rev() {
            if let Some(value) = scope.get(name) {
                return Ok(*value);
            }
        }
        Err(runtime_error(format!("undefined variable '{name}'")))
    }
}

fn truthy(value: i64) -> bool {
    value != 0
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ErrorKind {
    Lex,
    Parse,
    Runtime,
}

#[derive(Debug, Clone, PartialEq)]
struct CustError {
    kind: ErrorKind,
    line: usize,
    col: usize,
    message: String,
}

impl CustError {
    fn new(kind: ErrorKind, line: usize, col: usize, message: impl Into<String>) -> Self {
        Self {
            kind,
            line,
            col,
            message: message.into(),
        }
    }
}

impl fmt::Display for CustError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let label = match self.kind {
            ErrorKind::Lex => "lex error",
            ErrorKind::Parse => "parse error",
            ErrorKind::Runtime => "runtime error",
        };

        if self.kind == ErrorKind::Runtime {
            write!(f, "{label}: {}", self.message)
        } else {
            write!(f, "{label} at {}:{}: {}", self.line, self.col, self.message)
        }
    }
}

impl std::error::Error for CustError {}

fn runtime_error(message: impl Into<String>) -> CustError {
    CustError::new(ErrorKind::Runtime, 0, 0, message)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evaluates_arithmetic_and_precedence() {
        let outcome = run("print(1 + 2 * 3 - 4 / 2);").unwrap();
        assert_eq!(outcome.output, vec![5]);
    }

    #[test]
    fn executes_loop_and_assignment() {
        let source = r#"
            int i = 0;
            int sum = 0;
            while (i < 5) {
                sum = sum + i;
                i = i + 1;
            }
            print(sum);
        "#;
        let outcome = run(source).unwrap();
        assert_eq!(outcome.output, vec![10]);
    }

    #[test]
    fn handles_nested_scopes() {
        let source = r#"
            int x = 1;
            {
                int x = 2;
                print(x);
            }
            print(x);
        "#;
        let outcome = run(source).unwrap();
        assert_eq!(outcome.output, vec![2, 1]);
    }

    #[test]
    fn short_circuits_logical_operators() {
        let source = r#"
            int x = 0;
            if (0 && (10 / x)) {
                print(1);
            } else {
                print(2);
            }
            if (1 || (10 / x)) {
                print(3);
            }
        "#;
        let outcome = run(source).unwrap();
        assert_eq!(outcome.output, vec![2, 3]);
    }

    #[test]
    fn stops_on_return() {
        let source = r#"
            print(1);
            return 42;
            print(2);
        "#;
        let outcome = run(source).unwrap();
        assert_eq!(outcome.output, vec![1]);
        assert_eq!(outcome.return_value, Some(42));
    }

    #[test]
    fn rejects_undefined_variables() {
        let err = run("x = 1;").unwrap_err();
        assert_eq!(err.kind, ErrorKind::Runtime);
        assert!(err.message.contains("undefined variable"));
    }
}
