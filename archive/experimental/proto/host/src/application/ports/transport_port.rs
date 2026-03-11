pub trait TransportPort: Send {
    fn transport_name(&self) -> &'static str;
    fn connect(&mut self) -> Result<(), String>;
    fn send(&mut self, payload: &[u8], sequence: u64, keyframe: bool) -> Result<(), String>;
    fn stats(&self) -> String;
    fn disconnect(&mut self) -> Result<(), String>;
}
