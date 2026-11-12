//! Hands the pinned commit of the website's repository (the `rev` in Cargo.toml) to the generator, which writes it into
//! the generated file.
fn main() {
    println!("cargo:rerun-if-changed=Cargo.toml");
    let manifest = std::fs::read_to_string("Cargo.toml").expect("Cargo.toml");
    let rev = manifest
        .lines()
        .find(|line| line.contains("hanabi_protocol"))
        .and_then(|line| line.split("rev = \"").nth(1))
        .and_then(|rest| rest.split('"').next())
        .expect("the hanabi_protocol dependency has a rev");
    println!("cargo:rustc-env=HANABI_PROTOCOL_REV={rev}");
}
