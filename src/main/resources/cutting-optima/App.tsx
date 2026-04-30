import React from 'react';
import { HashRouter as Router, Routes, Route } from 'react-router-dom';
import { Layout } from './components/Layout';
import Home from './pages/Home';
import Preview from './pages/Preview';
import Validation from './pages/Validation';
import SequenceGroups from './pages/SequenceGroups';
import { AppProvider } from './context/AppContext';
import { SettingsProvider } from './context/SettingsContext';

const App = () => {
  return (
    <Router>
      <SettingsProvider>
        <AppProvider>
          <Layout>
            <Routes>
              <Route path="/" element={<Home />} />
              <Route path="/sequence-groups" element={<SequenceGroups />} />
              <Route path="/preview" element={<Preview />} />
              <Route path="/validation" element={<Validation />} />
            </Routes>
          </Layout>
        </AppProvider>
      </SettingsProvider>
    </Router>
  );
};

export default App;